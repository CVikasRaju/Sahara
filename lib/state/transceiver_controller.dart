import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart' as geo;
import 'package:shared_preferences/shared_preferences.dart';

import '../core/emergency_service.dart';
import '../ml/ibfs.dart';
import '../ml/languages.dart';
import '../ml/stt_engine.dart';
import '../ml/translation_engine.dart';
import '../ml/tts_engine.dart';
import '../ml/tts_model_downloader.dart';
import '../net/mesh_transport.dart';
import '../net/store_forward.dart';
import '../net/transport.dart';

/// ── Phase Enum (ARCHITECTURE.md §3) ─────────────────────────────

enum TransceiverPhase {
  idle, // listening for inbound only
  recording, // PTT held, mic capturing
  processing, // STT running on buffered audio
  transmitting, // frame on the wire
}

/// ── Log Entry ───────────────────────────────────────────────────

class LogEntry {
  final int id;
  final DateTime timestamp;
  final bool isSent; // true = transmitted, false = received
  final String text;
  final String langName;
  final Priority priority;
  final int? sttMs;
  final int? transferMs;
  final int? ttsMs;
  final int? e2eMs;
  final double? lat;
  final double? lon;
  final String? error;

  /// Whether this entry is an explicit SOS alert rather than a routine
  /// emergency-priority message.
  final bool sos;

  const LogEntry({
    required this.id,
    required this.timestamp,
    required this.isSent,
    required this.text,
    required this.langName,
    required this.priority,
    this.sttMs,
    this.transferMs,
    this.ttsMs,
    this.e2eMs,
    this.lat,
    this.lon,
    this.error,
    this.sos = false,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'ts': timestamp.millisecondsSinceEpoch,
        'sent': isSent,
        'text': text,
        'lang': langName,
        'priority': priority.value,
        'sttMs': sttMs,
        'txMs': transferMs,
        'ttsMs': ttsMs,
        'e2eMs': e2eMs,
        'lat': lat,
        'lon': lon,
        'error': error,
        'sos': sos,
      };

  factory LogEntry.fromJson(Map<String, dynamic> j) => LogEntry(
        id: j['id'] as int,
        timestamp: DateTime.fromMillisecondsSinceEpoch(j['ts'] as int),
        isSent: j['sent'] as bool,
        text: j['text'] as String,
        langName: j['lang'] as String,
        priority: Priority.values.firstWhere(
          (p) => p.value == j['priority'],
          orElse: () => Priority.routine,
        ),
        sttMs: j['sttMs'] as int?,
        transferMs: j['txMs'] as int?,
        ttsMs: j['ttsMs'] as int?,
        e2eMs: j['e2eMs'] as int?,
        lat: (j['lat'] as num?)?.toDouble(),
        lon: (j['lon'] as num?)?.toDouble(),
        error: j['error'] as String?,
        sos: j['sos'] as bool? ?? false,
      );
}

/// ── Transceiver Controller ──────────────────────────────────────

/// Core PTT state machine (ARCHITECTURE.md §3).
///
/// All mutation flows through this class. Widgets are projections of
/// its [ValueNotifier] fields.
class TransceiverController extends ChangeNotifier {
  final SttEngine stt;
  final TtsEngine tts;
  final Transport transport;
  final TranslationEngine translator;

  late final StoreForwardQueue storeForward;

  TransceiverController({
    required this.stt,
    required this.tts,
    required this.transport,
    TranslationEngine? translator,
  }) : translator = translator ?? TranslationEngine() {
    storeForward = StoreForwardQueue(transport);
    _listenInbound();
    _loadPrefs();

    // Mirror radio-state changes into the UI. The mesh emits only when the
    // link signature actually changes, so this does not cause rebuild churn.
    final t = transport;
    if (t is MeshTransport) {
      _linkSubscription = t.linkChanged.listen((_) => notifyListeners());
    }

    // Delay radio start so the runtime permission dialog can be answered
    // first. HomeScreen._requestPermissions() also calls enableMesh() as soon
    // as permissions are granted; this timer is the fallback path.
    Future.delayed(const Duration(seconds: 3), () {
      if (!_meshActive) _startMeshOnInit();
    });
  }

  StreamSubscription<void>? _linkSubscription;

  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    _gpsEnabled = prefs.getBool('gpsEnabled') ?? true;
    notifyListeners();
  }

  /// Auto-start the radios in the background.
  Future<void> _startMeshOnInit() async {
    final t = transport;
    if (t is! MeshTransport) return;
    final ok = await t.start();
    _meshActive = ok;
    if (!ok) _statusMessage = t.stats.failureHint ?? _statusMessage;
    notifyListeners();

    // Standby keeps this isolate alive with no Activity attached, which is what
    // lets an SOS arrive when the app looks closed. Started regardless of the
    // radio result: it also keeps the radio retry loop running.
    await enableStandby();
  }

  /// Live radio state (BLE + Wi-Fi Direct), for the app-bar badge.
  LinkStats get linkStats {
    final t = transport;
    if (t is MeshTransport) return t.stats;
    return const LinkStats(
      bleRunning: false,
      bleScanning: false,
      blePeers: 0,
      bleKnownPeers: 0,
      bleStatus: 'off',
      wifiDirectRunning: false,
      wifiDirectSupported: false,
      wifiDirectPeers: 0,
      wifiDirectStatus: 'off',
    );
  }

  /// Short badge text: '2 peers' / 'searching' / 'offline'.
  String get linkLabel => linkStats.label;

  /// Verbose radio state, e.g. 'BLE 1 peer · Wi-Fi Direct connected'.
  String get linkDetail => linkStats.detail;

  // ── State ──────────────────────────────────────────────────────
  TransceiverPhase _phase = TransceiverPhase.idle;
  TransceiverPhase get phase => _phase;

  bool _gpsEnabled = true; // Default on — users expect GPS to work out of the box.
  bool get gpsEnabled => _gpsEnabled;
  set gpsEnabled(bool v) {
    _gpsEnabled = v;
    notifyListeners();
    // Persist preference.
    SharedPreferences.getInstance().then((p) => p.setBool('gpsEnabled', v));
  }

  Lang _senderLang = kHindi;
  Lang get senderLang => _senderLang;
  set senderLang(Lang v) {
    _senderLang = v;
    notifyListeners();
  }

  Lang _receiverLang = kHindi;
  Lang get receiverLang => _receiverLang;
  set receiverLang(Lang v) {
    _receiverLang = v;
    notifyListeners();
    // Prepare the neural voice for the new receiver language.
    _ensureTtsModels(v);
  }

  String _interimText = '';
  String get interimText => _interimText;

  String? _statusMessage;
  String? get statusMessage => _statusMessage;
  void clearStatusMessage() {
    _statusMessage = null;
    notifyListeners();
  }

  /// Per-launch random prefix for the sequence ID.
  ///
  /// The Sequence ID is the *only* identity an iBFS frame carries, and it is
  /// also the mesh dedup key (NETWORK_PROTOCOL.md §5). If two devices both
  /// counted up from 1 they would each treat the other's first packets as
  /// already-seen duplicates and silently drop them. Seeding the high 16 bits
  /// from a random value keeps concurrent senders apart without changing the
  /// wire format.
  final Random _rng = Random();
  late int _sequenceId = _rng.nextInt(0x10000) << 16;

  /// Typed text to send (fallback when STT is unavailable).
  String _typedText = '';
  String get typedText => _typedText;
  set typedText(String v) {
    _typedText = v;
    notifyListeners();
  }

  bool _alarmActive = false;
  bool get alarmActive => _alarmActive;

  Priority _alarmPriority = Priority.emergency;
  Priority get alarmPriority => _alarmPriority;

  /// Text of the message that raised the current alarm.
  String? _alarmText;
  String? get alarmText => _alarmText;

  /// 'SOS' or 'EMERGENCY' — shown in the alarm banner.
  String _alarmLabel = 'EMERGENCY';
  String get alarmLabel => _alarmLabel;

  /// Whether the current alarm came from an explicit SOS packet.
  bool _alarmIsSos = false;
  bool get alarmIsSos => _alarmIsSos;

  /// When the current alarm started, used to ignore stale auto-clear timers
  /// when a second alert arrives while one is already showing.
  DateTime? _alarmStartedAt;
  Timer? _alarmTimer;

  /// Number of devices the last SOS was handed to.
  int _lastSosFanout = 0;
  int get lastSosFanout => _lastSosFanout;

  final List<LogEntry> _log = [];
  List<LogEntry> get log => List.unmodifiable(_log);

  // ── Model Download State ───────────────────────────────────────

  bool _modelsDownloading = false;
  bool get modelsDownloading => _modelsDownloading;

  double _modelsDownloadProgress = 0.0;
  double get modelsDownloadProgress => _modelsDownloadProgress;

  String _modelsDownloadStatus = '';
  String get modelsDownloadStatus => _modelsDownloadStatus;

  /// Whether the sender language models are ready for offline STT.
  bool get senderModelsReady => stt.isReady && stt.currentLocale == _senderLang.code;

  // ── TTS Model Download State ──────────────────────────────────

  bool _ttsDownloading = false;
  bool get ttsDownloading => _ttsDownloading;

  double _ttsDownloadProgress = 0.0;
  double get ttsDownloadProgress => _ttsDownloadProgress;

  String _ttsDownloadStatus = '';
  String get ttsDownloadStatus => _ttsDownloadStatus;

  /// Whether the receiver language has a neural TTS engine ready.
  bool get receiverTtsReady => tts.isNeuralReadyFor(_receiverLang);

  /// Download + initialize the neural TTS model for the receiver language.
  Future<bool> downloadReceiverTtsModels() async {
    return _ensureTtsModels(_receiverLang);
  }

  Future<bool> _ensureTtsModels(Lang lang) async {
    // Already loaded for THIS language?
    if (tts.isNeuralReadyFor(lang)) return true;
    // No neural model exists for this language (e.g. Odia) — platform TTS.
    if (!TtsModelDownloader.hasNeuralModel(lang)) return false;
    if (_ttsDownloading) return false;

    _ttsDownloading = true;
    _ttsDownloadProgress = 0.0;
    _ttsDownloadStatus = 'Preparing ${lang.name} voice…';
    notifyListeners();

    // Download if not present.
    var available = await TtsModelDownloader.areModelsAvailable(lang);
    if (!available) {
      available = await TtsModelDownloader.downloadModels(
        lang,
        onProgress: (p) {
          _ttsDownloadProgress = p;
          _ttsDownloadStatus =
              'Downloading ${lang.name} voice… ${(p * 100).toInt()}%';
          notifyListeners();
        },
      );
    }

    _ttsDownloading = false;
    if (available) {
      final loaded = await tts.initNeural(lang);
      _ttsDownloadStatus = loaded
          ? '${lang.name} neural voice ready'
          : '${lang.name} voice unavailable — using platform TTS';
    } else {
      _ttsDownloadStatus = 'Voice download failed — check connection';
    }
    notifyListeners();
    return available;
  }

  /// Download models for the current sender language.
  Future<void> downloadSenderModels() async {
    if (_modelsDownloading) return;

    _modelsDownloading = true;
    _modelsDownloadProgress = 0.0;
    _modelsDownloadStatus = 'Preparing ${_senderLang.name} models…';
    notifyListeners();

    final success = await stt.prepareModels(
      _senderLang,
      onProgress: (progress) {
        _modelsDownloadProgress = progress;
        _modelsDownloadStatus =
            'Downloading ${_senderLang.name} models… ${(progress * 100).toInt()}%';
        notifyListeners();
      },
    );

    _modelsDownloading = false;
    if (success) {
      // Auto-initialize the recognizer with the new models.
      final initErr = await stt.init(_senderLang);
      if (initErr == null) {
        _modelsDownloadStatus = '${_senderLang.name} models ready ✓';
      } else {
        // Show the real sherpa-onnx error to the user.
        _modelsDownloadStatus = 'Load failed: $initErr';
      }
    } else {
      _modelsDownloadStatus = 'Download failed — check connection';
    }
    notifyListeners();
  }

  /// Pre-download models for a language in the background.
  Future<void> predownloadModels(Lang lang) async {
    if (_modelsDownloading) return;

    _modelsDownloading = true;
    _modelsDownloadProgress = 0.0;
    _modelsDownloadStatus = 'Preparing ${lang.name} models…';
    notifyListeners();

    final success = await stt.prepareModels(
      lang,
      onProgress: (progress) {
        _modelsDownloadProgress = progress;
        _modelsDownloadStatus =
            'Downloading ${lang.name} models… ${(progress * 100).toInt()}%';
        notifyListeners();
      },
    );

    _modelsDownloading = false;
    if (success) {
      final initErr = await stt.init(lang);
      if (initErr == null) {
        _modelsDownloadStatus = '${lang.name} models ready ✓';
      } else {
        _modelsDownloadStatus = 'Load failed: $initErr';
      }
    } else {
      _modelsDownloadStatus = 'Download failed — check connection';
    }
    notifyListeners();
  }

  // ── Mesh Transport ────────────────────────────────────────────

  bool _meshActive = false;

  /// Whether at least one radio is up. `false` only when both radios failed.
  bool get meshActive => _meshActive;

  /// Start (or re-start) the radios. Idempotent — safe to call on a timer.
  Future<bool> enableMesh() async {
    final t = transport;
    if (t is! MeshTransport) return false;
    if (_meshActive) return true;
    final ok = await t.start();
    _meshActive = ok;
    // Both radios down is actionable, not mysterious: say which one refused
    // and what to switch on.
    if (!ok) _statusMessage = t.stats.failureHint ?? _statusMessage;
    notifyListeners();
    return ok;
  }

  /// Number of peers reachable across both radios.
  int get meshPeerCount => linkStats.totalPeers;

  /// Whether a frame can reach another device right now.
  bool get hasReachablePeer => linkStats.hasPeers;

  // ── PTT Controls ───────────────────────────────────────────────

  int? _sttStartMs;

  bool get isRecording => _phase == TransceiverPhase.recording;
  bool get isProcessing => _phase == TransceiverPhase.processing;

  /// Begin recording on PTT press or tap.
  Future<void> startPtt() async {
    if (_phase != TransceiverPhase.idle) return;

    _statusMessage = null;
    _phase = TransceiverPhase.recording;
    _interimText = '';
    _sttStartMs = DateTime.now().millisecondsSinceEpoch;
    notifyListeners();

    // If the offline STT model isn't loaded yet, kick off a download in
    // the background. The hold still captures audio (VAD + fallbacks),
    // so the user is never blocked — but without this, first-time users
    // only ever see "No speech detected" until they find the download
    // button manually.
    if (!stt.isReady || stt.currentLocale != _senderLang.code) {
      downloadSenderModels();
      _statusMessage =
          'Preparing ${_senderLang.name} speech model — voice captured, '
          'transcription improves when the model finishes downloading';
      notifyListeners();
    }

    await stt.start(
      localeId: _senderLang.code,
      onResult: (text, isFinal) {
        _interimText = text;
        notifyListeners();
        if (isFinal && text.trim().isNotEmpty) {
          _processTranscript(text);
        }
      },
    );

    // If stt.start() finished without starting the recorder (e.g. error),
    // reset phase to idle so UI recovers.
    if (!stt.isListening && _phase == TransceiverPhase.recording) {
      _phase = TransceiverPhase.idle;
      _statusMessage = 'Microphone not available — check permissions';
      notifyListeners();
    }
  }

  /// Stop recording on PTT release or second tap; process speech.
  Future<void> stopPtt() async {
    if (_phase != TransceiverPhase.recording) return;

    _phase = TransceiverPhase.processing;
    notifyListeners();

    // If recorder was still activating (mic stream setup taking 100-300ms),
    // wait up to 1.5s for recorder to activate so speech is not lost!
    for (var i = 0; i < 15 && !stt.isListening; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }

    if (!stt.isListening) {
      _phase = TransceiverPhase.idle;
      _interimText = '';
      _statusMessage = 'Microphone was not active — tap and speak';
      notifyListeners();
      return;
    }

    // stop() flushes any speech segment still inside the VAD pipeline,
    // so a short utterance spoken right before release is not lost.
    final flushed = await stt.stop();
    final text = (flushed.trim().isNotEmpty ? flushed : _interimText).trim();

    if (text.isNotEmpty) {
      await _processTranscript(text);
    } else {
      _phase = TransceiverPhase.idle;
      _interimText = '';
      // Distinguish "mic never started" from "mic ran but heard nothing".
      // Blanket "speak clearly" advice is wrong when the real cause is a
      // model still downloading or a voice too quiet for the STT model.
      _statusMessage = stt.isReady
          ? 'No transcription — speak louder and closer to the mic'
          : 'Speech model still downloading — tap PTT again in a moment '
              'or type your message below';
      notifyListeners();
    }
  }

  /// Process the final transcript and transmit.
  Future<void> _processTranscript(String text) async {
    if (text.trim().isEmpty) {
      _phase = TransceiverPhase.idle;
      _interimText = '';
      notifyListeners();
      return;
    }

    // Ensure phase is processing (Encode stage active)
    _phase = TransceiverPhase.processing;
    notifyListeners();

    final e2eStart = DateTime.now().millisecondsSinceEpoch;
    final sttMs = _sttStartMs != null
        ? e2eStart - _sttStartMs!
        : 0;
    _sttStartMs = null;

    // ── Distress detection (ADDITIONAL_FEATURES.md §1) ──
    final isDistress = detectDistress(text, _senderLang.iso639);
    final priority = isDistress ? Priority.emergency : Priority.routine;

    // ── GPS stamping (ADDITIONAL_FEATURES.md §2) ──
    final (lat, lon) = await _stampGps();

    // ── Encode ──
    _sequenceId++;
    final flags = PayloadFlags(
      hasGps: lat != null && lon != null,
    );

    final packet = IbfPacket(
      type: PacketType.pttVoice,
      priority: priority,
      language: _senderLang,
      sequenceId: _sequenceId,
      text: text,
      flags: flags,
      latitude: lat,
      longitude: lon,
    );

    final frame = encodeIbfs(packet);

    // Brief visual pause so the user sees the Encode stage light up
    await Future<void>.delayed(const Duration(milliseconds: 150));

    // ── Transmit ──
    _phase = TransceiverPhase.transmitting;
    notifyListeners();

    int transferMs;
    try {
      transferMs = await transport.send(frame);
    } catch (e) {
      // Queue for store-and-forward (ADDITIONAL_FEATURES.md §3).
      final reason = e is StateError ? e.message : e.toString();
      storeForward.enqueue(frame, sequenceId: _sequenceId);
      _addLog(LogEntry(
        id: _sequenceId,
        timestamp: DateTime.now(),
        isSent: true,
        text: text,
        langName: _senderLang.name,
        priority: priority,
        sttMs: sttMs,
        lat: lat,
        lon: lon,
        error: 'Not delivered — $reason\nQueued '
            '(${storeForward.pendingCount}) for automatic retry',
      ));
      _phase = TransceiverPhase.idle;
      _interimText = '';
      notifyListeners();
      return;
    }

    final e2eMs = DateTime.now().millisecondsSinceEpoch - e2eStart;

    _addLog(LogEntry(
      id: _sequenceId,
      timestamp: DateTime.now(),
      isSent: true,
      text: text,
      langName: _senderLang.name,
      priority: priority,
      sttMs: sttMs,
      transferMs: transferMs,
      e2eMs: e2eMs,
      lat: lat,
      lon: lon,
    ));

    _phase = TransceiverPhase.idle;
    _interimText = '';
    notifyListeners();
  }

  /// Best-effort GPS fix for an outgoing frame.
  ///
  /// Never throws and never blocks for long: an SOS must not wait on a
  /// satellite, and a missing fix is better than a late alert.
  Future<(double?, double?)> _stampGps({
    Duration timeout = const Duration(seconds: 5),
  }) async {
    if (!_gpsEnabled) return (null, null);
    try {
      final pos = await geo.Geolocator.getCurrentPosition(
        desiredAccuracy: geo.LocationAccuracy.low,
        timeLimit: timeout,
      );
      return (pos.latitude, pos.longitude);
    } catch (_) {
      return (null, null);
    }
  }

  // ── SOS + Emergency Standby ────────────────────────────────────

  bool _standbyActive = false;

  /// Whether Android is keeping iTantra alive in the background, so SOS
  /// signals are received even with the app swiped off the recents list.
  bool get standbyActive => _standbyActive;

  bool _dndAccess = false;

  /// Whether the user granted Do Not Disturb access.
  ///
  /// The alarm tone is exempt from DND either way; this access is what also
  /// lifts DND so the *spoken* message is audible.
  bool get dndAccess => _dndAccess;

  bool _batteryExempt = true;

  /// Whether the app is exempt from battery optimisation. Without it, OEM
  /// battery savers can kill standby and with it SOS reception.
  bool get batteryExempt => _batteryExempt;

  bool _sosInFlight = false;
  bool get sosInFlight => _sosInFlight;

  DateTime? _lastSosSentAt;
  DateTime? get lastSosSentAt => _lastSosSentAt;

  /// Re-read native emergency state (called when the app resumes, so status
  /// updates after the user returns from a settings screen).
  Future<void> refreshEmergencyState() async {
    final standby = await EmergencyService.isStandbyRunning();
    final dnd = await EmergencyService.hasDndAccess();
    final battery = await EmergencyService.isIgnoringBatteryOptimizations();
    if (standby == _standbyActive &&
        dnd == _dndAccess &&
        battery == _batteryExempt) {
      return;
    }
    _standbyActive = standby;
    _dndAccess = dnd;
    _batteryExempt = battery;
    notifyListeners();
  }

  /// Turn on background standby. Returns `true` when active.
  Future<bool> enableStandby() async {
    final ok = await EmergencyService.startStandby();
    if (ok != _standbyActive) {
      _standbyActive = ok;
      notifyListeners();
    }
    return ok;
  }

  Future<void> disableStandby() async {
    await EmergencyService.stopStandby();
    _standbyActive = false;
    notifyListeners();
  }

  Future<void> openDndSettings() => EmergencyService.openDndSettings();

  Future<void> openBatterySettings() => EmergencyService.openBatterySettings();

  /// Broadcast an SOS to every nearby device.
  ///
  /// This is deliberately not a priority flag on a normal message: an SOS is
  /// [PacketType.silentSos], which every receiver treats as "raise the alarm",
  /// including receivers whose app is not on screen.
  ///
  /// Returns `true` when at least one device was reached. When nothing is in
  /// range the frame is queued and re-sent automatically on reconnect.
  Future<bool> sendSos({String? note}) async {
    if (_sosInFlight) return false;
    _sosInFlight = true;
    notifyListeners();

    try {
      // Shorter GPS budget than a normal message: alert first, locate second.
      final (lat, lon) = await _stampGps(
        timeout: const Duration(seconds: 3),
      );

      _sequenceId++;
      final text = (note == null || note.trim().isEmpty)
          ? 'SOS'
          : 'SOS — ${note.trim()}';

      final packet = IbfPacket(
        type: PacketType.silentSos,
        priority: Priority.emergency,
        language: _senderLang,
        sequenceId: _sequenceId,
        text: text,
        flags: PayloadFlags(hasGps: lat != null && lon != null),
        latitude: lat,
        longitude: lon,
      );
      final frame = encodeIbfs(packet);

      int fanout;
      try {
        fanout = await transport.send(frame);
      } catch (e) {
        final reason = e is StateError ? e.message : e.toString();
        storeForward.enqueue(frame, sequenceId: _sequenceId);
        _lastSosFanout = 0;
        _lastSosSentAt = DateTime.now();
        _statusMessage = 'SOS queued — $reason. It will be sent automatically '
            'as soon as a device is in range.';
        _addLog(LogEntry(
          id: _sequenceId,
          timestamp: DateTime.now(),
          isSent: true,
          text: text,
          langName: _senderLang.name,
          priority: Priority.emergency,
          lat: lat,
          lon: lon,
          sos: true,
          error: 'Not delivered — $reason\nQueued '
              '(${storeForward.pendingCount}) for automatic retry',
        ));
        return false;
      }

      _lastSosFanout = fanout;
      _lastSosSentAt = DateTime.now();
      _statusMessage = fanout == 1
          ? 'SOS sent to 1 device'
          : 'SOS sent to $fanout devices';
      _addLog(LogEntry(
        id: _sequenceId,
        timestamp: DateTime.now(),
        isSent: true,
        text: text,
        langName: _senderLang.name,
        priority: Priority.emergency,
        lat: lat,
        lon: lon,
        sos: true,
      ));
      return fanout > 0;
    } finally {
      _sosInFlight = false;
      notifyListeners();
    }
  }

  /// Raise the alarm overlay and the native loud alert.
  ///
  /// Not awaited by the inbound path: the alarm must never block the next
  /// packet from being processed, and a second SOS arriving during an alert
  /// extends it rather than being dropped.
  void _raiseAlarm(IbfPacket packet, String displayText) {
    final startedAt = DateTime.now();
    _alarmStartedAt = startedAt;
    _alarmActive = true;
    _alarmPriority = packet.priority;
    _alarmIsSos = packet.isSos;
    _alarmLabel = packet.alertLabel;
    _alarmText = displayText;
    notifyListeners();

    // Native alert first: a loud alarm on the alarm stream, a repeating
    // vibration and a full-screen notification. This is what makes the SOS
    // audible when no widget tree is mounted, on silent mode, and through
    // Do Not Disturb.
    unawaited(EmergencyService.raiseAlarm(
      text: displayText,
      from: packet.alertLabel,
    ));

    _alarmTimer?.cancel();
    _alarmTimer = Timer(
      packet.isSos ? const Duration(seconds: 20) : const Duration(seconds: 9),
      () {
        // Ignore a stale timer if a newer alert replaced this one.
        if (_alarmStartedAt != startedAt) return;
        _alarmActive = false;
        notifyListeners();
        unawaited(EmergencyService.clearAlarm());
      },
    );
  }

  // ── Receive Path ───────────────────────────────────────────────

  /// Inbound frames are handled strictly one at a time. Frames can arrive
  /// back-to-back on PTT release, and overlapping TTS calls would speak over
  /// each other.
  Future<void> _inboundChain = Future<void>.value();

  void _listenInbound() {
    transport.incoming.listen((bytes) {
      _inboundChain = _inboundChain.then((_) => _handleInbound(bytes));
    });
  }

  Future<void> _handleInbound(Uint8List bytes) async {
    final e2eStart = DateTime.now().millisecondsSinceEpoch;

    // ── Decode ──
    IbfPacket packet;
    try {
      packet = decodeIbfs(bytes);
    } catch (e) {
      _addLog(LogEntry(
        id: -1,
        timestamp: DateTime.now(),
        isSent: false,
        text: '[Corrupt frame dropped]',
        langName: '—',
        priority: Priority.routine,
        error: e.toString(),
      ));
      return;
    }

    // ── Half-duplex (PTT) discipline ──
    // A walkie-talkie is half duplex: while this device is recording,
    // processing or transmitting, the incoming voice is logged but NOT played,
    // otherwise the speaker would be picked up by our own microphone and
    // immediately re-transmitted.
    final bool busyTransmitting = _phase != TransceiverPhase.idle;

    // ── Cross-lingual translation + neural TTS (ARCHITECTURE.md §2.4) ──
    // If the packet language differs from our receiver language, translate
    // the text on-device, then speak the translation with the receiver
    // language's neural voice.
    final bool sameLang = packet.language.iso639 == _receiverLang.iso639;
    String displayText = packet.text;
    String spokenText = packet.text;
    Lang ttsLang = packet.language;

    if (!sameLang) {
      // Ensure the receiver's voice is available (downloads once, ~114 MB).
      // Skipped while we are on air so a download never delays the next packet.
      if (!busyTransmitting) await _ensureTtsModels(_receiverLang);

      final translated = await translator.translate(
        packet.text,
        packet.language,
        _receiverLang,
      );
      if (translated != null) {
        displayText = '${packet.text} → $translated';
        spokenText = translated;
        ttsLang = _receiverLang;
      }
      // Translation unavailable: fall back to showing the original text.
    } else if (!busyTransmitting) {
      // Same language: still make sure the neural voice is ready.
      await _ensureTtsModels(_receiverLang);
    }

    final int? ttsMs;
    if (busyTransmitting) {
      // Received while we were on air — show it, stay silent.
      ttsMs = null;
    } else {
      // For an SOS, let the alarm tone land first: the burst grabs attention
      // and the spoken message then gets through the gaps.
      if (packet.isSos) {
        await Future<void>.delayed(const Duration(milliseconds: 1500));
      }
      final ttsStart = DateTime.now().millisecondsSinceEpoch;
      await tts.speak(spokenText,
          lang: ttsLang, emergency: packet.raisesAlarm);
      ttsMs = DateTime.now().millisecondsSinceEpoch - ttsStart;
    }

    final e2eMs = DateTime.now().millisecondsSinceEpoch - e2eStart;

    _addLog(LogEntry(
      id: packet.sequenceId,
      timestamp: DateTime.now(),
      isSent: false,
      text: displayText,
      langName: sameLang
          ? packet.language.name
          : '${packet.language.name} → ${_receiverLang.name}',
      priority: packet.priority,
      ttsMs: ttsMs,
      e2eMs: e2eMs,
      lat: packet.latitude,
      lon: packet.longitude,
      sos: packet.isSos,
    ));

    // ── Emergency alarm override (ARCHITECTURE.md §2.3) ──
    // Fired last and never awaited, so the alert cannot stall the receive
    // path for the next packet.
    if (packet.raisesAlarm) {
      _raiseAlarm(packet, displayText);
    }
  }

  /// Manually dismiss the alarm (also silences the native alert).
  void dismissAlarm() {
    _alarmTimer?.cancel();
    _alarmTimer = null;
    _alarmActive = false;
    _alarmText = null;
    notifyListeners();
    unawaited(EmergencyService.clearAlarm());
  }

  // ── Log persistence ────────────────────────────────────────────

  void _addLog(LogEntry entry) {
    _log.add(entry);
    notifyListeners();
    _persistLog();
  }

  Future<void> _persistLog() async {
    final prefs = await SharedPreferences.getInstance();
    final json = _log.map((e) => e.toJson()).toList();
    await prefs.setString('itantra_log', jsonEncode(json));
  }

  /// Load persisted log from disk.
  Future<void> loadLog() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString('itantra_log');
    if (raw == null) return;
    try {
      final list = jsonDecode(raw) as List;
      _log.clear();
      _log.addAll(list.map((j) => LogEntry.fromJson(j as Map<String, dynamic>)));
      notifyListeners();
    } catch (_) {
      // Corrupted log — start fresh.
    }
  }

  /// Send typed text directly (bypasses STT).
  Future<void> sendTypedText(String text) async {
    if (text.trim().isEmpty) return;
    if (_phase != TransceiverPhase.idle) return;

    _typedText = '';
    notifyListeners();
    await _processTranscript(text.trim());
  }

  /// Number of queued messages waiting for peer reconnection.
  int get queuedCount => storeForward.pendingCount;
  bool get hasQueuedMessages => storeForward.hasPending;

  /// Clear the packet log.
  Future<void> clearLog() async {
    _log.clear();
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('itantra_log');
  }

  @override
  void dispose() {
    _linkSubscription?.cancel();
    _alarmTimer?.cancel();
    transport.disconnect();
    stt.dispose();
    tts.dispose();
    translator.dispose();
    super.dispose();
  }
}
