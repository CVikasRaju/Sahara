import 'dart:async';

import 'package:flutter/foundation.dart';

import 'ble_transport.dart';
import 'transport.dart';
import 'wifi_direct_transport.dart';

/// Snapshot of the live radio state, for the UI and for logging.
class LinkStats {
  const LinkStats({
    required this.bleRunning,
    required this.bleScanning,
    required this.blePeers,
    required this.bleKnownPeers,
    required this.bleStatus,
    required this.wifiDirectRunning,
    required this.wifiDirectSupported,
    required this.wifiDirectPeers,
    required this.wifiDirectStatus,
    this.bleError,
    this.wifiDirectError,
  });

  final bool bleRunning;
  final bool bleScanning;
  final int blePeers;
  final int bleKnownPeers;
  final String bleStatus;

  final bool wifiDirectRunning;
  final bool wifiDirectSupported;
  final int wifiDirectPeers;
  final String wifiDirectStatus;

  /// Why Bluetooth failed to start, if it did.
  final String? bleError;

  /// Why Wi-Fi Direct failed to start, if it did.
  final String? wifiDirectError;

  /// Peers we can exchange frames with, across all radios.
  int get totalPeers => blePeers + wifiDirectPeers;

  /// Whether any radio is up (a link may exist even with 0 peers so far).
  bool get anyRunning => bleRunning || wifiDirectRunning;

  bool get hasPeers => totalPeers > 0;

  /// Short badge text (the app-bar pill).
  String get label {
    if (hasPeers) return '$totalPeers peer${totalPeers == 1 ? '' : 's'}';
    if (anyRunning) return bleScanning ? 'searching' : 'listening';
    return 'offline';
  }

  /// A single actionable sentence when both radios are down, otherwise null.
  String? get failureHint {
    if (anyRunning) return null;
    final parts = <String>[
      if (bleError != null) 'Bluetooth: $bleError',
      if (wifiDirectError != null) 'Wi-Fi Direct: $wifiDirectError',
    ];
    if (parts.isEmpty) {
      return 'No radio is available — turn on Bluetooth, or turn on Wi-Fi '
          '(you do not need to join a network) for Wi-Fi Direct.';
    }
    return '${parts.join(' · ')}. Turn on Bluetooth (and Wi-Fi for Wi-Fi '
        'Direct — no network or hotspot needed).';
  }

  /// Actionable guidance shown while a radio is up but no peer has been found
  /// yet. Without it "searching" looks identical to "broken".
  String get searchHint {
    if (hasPeers) return '';
    if (!anyRunning) return failureHint ?? '';
    return 'Radios are up but no peer yet — keep iTantra open on the other '
        'phone, and make sure Bluetooth and Wi-Fi are on and Location is '
        'enabled (Android hides BLE and Wi-Fi Direct scan results when '
        'Location is off).';
  }

  /// Long form for tooltips / the status banner.
  String get detail {
    final ble = bleRunning ? 'BLE $bleStatus' : 'BLE off';
    final wifi = !wifiDirectSupported
        ? 'Wi-Fi Direct unsupported'
        : wifiDirectRunning
            ? 'Wi-Fi Direct $wifiDirectStatus'
            : 'Wi-Fi Direct idle';
    return '$ble · $wifi';
  }

  /// Used to detect changes without spamming the UI.
  String get signature =>
      '$bleRunning/$bleScanning/$blePeers/$bleKnownPeers/$wifiDirectRunning/'
      '$wifiDirectPeers/$wifiDirectStatus';
}

/// Aggregates every available radio into one [Transport].
///
/// Both radios carry the same frames concurrently; inbound frames are
/// deduplicated by iBFS sequence ID so a message delivered over BLE *and*
/// Wi-Fi Direct is still spoken exactly once. This is what makes the app
/// genuinely zero-infrastructure: no hotspot, no router, no pairing, no
/// host/client split — every device transmits and receives on equal terms.
///
/// Radio policy (2.4 GHz is shared between BLE and Wi-Fi on every phone, which
/// is why "Wi-Fi connected ⇒ BLE stops working" happens):
///  * BLE starts immediately — it is the low-power, fastest-to-connect path.
///  * Wi-Fi Direct is held back for [_wifiGraceMs] while BLE is still finding
///    peers, so the two radios do not fight during initial connection setup.
///  * If BLE is up but still has no peer after the grace period (exactly the
///    case when an active Wi-Fi link is starving BLE discovery), Wi-Fi Direct
///    is started so the link can still form.
///  * If BLE cannot start at all, Wi-Fi Direct starts straight away.
class MeshTransport implements Transport {
  MeshTransport({
    BleMeshTransport? ble,
    WifiDirectTransport? wifiDirect,
    this.wifiGrace = const Duration(seconds: 12),
  })  : _ble = ble ?? BleMeshTransport.instance,
        _wifiDirect = wifiDirect ?? WifiDirectTransport();

  final BleMeshTransport _ble;
  final WifiDirectTransport _wifiDirect;

  /// How long BLE is given to find a peer before Wi-Fi Direct joins in.
  final Duration wifiGrace;

  final _controller = StreamController<Uint8List>.broadcast();
  final _linkChanges = StreamController<void>.broadcast();

  /// Cross-link dedup: iBFS sequence ID → arrival ms.
  final Map<int, int> _seen = {};
  static const int _dedupTtlMs = 120000;

  StreamSubscription? _bleSub;
  StreamSubscription? _wifiSub;
  Timer? _pollTimer;
  Timer? _healthTimer;

  /// Retry cadence when Wi-Fi Direct refuses to start (permission missing,
  /// Wi-Fi radio off, OEM restriction). The user may fix it mid-session.
  static const int _wifiDirectRetryMs = 30000;

  bool _shouldRun = false;
  int _nextWifiDirectAttemptMs = 0;
  DateTime? _startedAt;
  String _lastSignature = '';

  BleMeshTransport get ble => _ble;
  WifiDirectTransport get wifiDirect => _wifiDirect;

  /// Fires whenever the link signature changes (peers, radio state).
  Stream<void> get linkChanged => _linkChanges.stream;

  LinkStats get stats => LinkStats(
        bleRunning: _ble.isRunning,
        bleScanning: _ble.isScanning,
        blePeers: _ble.peerCount,
        bleKnownPeers: _ble.knownPeerCount,
        bleStatus: _ble.status,
        wifiDirectRunning: _wifiDirect.isRunning,
        wifiDirectSupported: _wifiDirect.isSupported,
        wifiDirectPeers: _wifiDirect.peerCount,
        wifiDirectStatus: _wifiDirect.status,
        bleError: _ble.lastError,
        wifiDirectError: _wifiDirect.lastError,
      );

  @override
  Stream<Uint8List> get incoming => _controller.stream;

  /// True when at least one radio is running, so callers can distinguish
  /// "no peers yet" from "radios are off".
  @override
  bool get isConnected => stats.anyRunning;

  /// Whether a frame can actually reach another device right now.
  bool get hasPeers => stats.hasPeers;

  /// Bring both radios up. Returns `true` when at least one radio started.
  Future<bool> start() async {
    _shouldRun = true;
    _startedAt ??= DateTime.now();

    _bleSub ??= _ble.incoming.listen(_onInbound);
    _wifiSub ??= _wifiDirect.incoming.listen(_onInbound);

    _pollTimer ??= Timer.periodic(const Duration(seconds: 2), (_) => _emitChanges());
    _healthTimer ??=
        Timer.periodic(const Duration(seconds: 6), (_) => _healthCheck());

    final bleOk = await _ble.start();
    if (!bleOk) {
      // BLE refused (radio off, permission denied, unsupported): fall through
      // to Wi-Fi Direct immediately rather than leaving the user with nothing.
      debugPrint('MeshTransport: BLE unavailable — starting Wi-Fi Direct');
      await _ensureWifiDirect(force: true);
    }

    _emitChanges(force: true);
    return bleOk || _wifiDirect.isRunning;
  }

  /// Retry BLE if it dropped, and bring Wi-Fi Direct up once the grace
  /// period has elapsed without a BLE peer.
  Future<void> _healthCheck() async {
    if (!_shouldRun) return;

    if (!_ble.isRunning) {
      final ok = await _ble.start();
      if (ok) debugPrint('MeshTransport: BLE restarted');
    }

    final elapsed = _startedAt == null
        ? Duration.zero
        : DateTime.now().difference(_startedAt!);
    if (elapsed >= wifiGrace || !_ble.isRunning) {
      await _ensureWifiDirect();
    }

    _emitChanges();
  }

  Future<void> _ensureWifiDirect({bool force = false}) async {
    if (_wifiDirect.isRunning) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    if (!force && now < _nextWifiDirectAttemptMs) return;
    _nextWifiDirectAttemptMs = now + _wifiDirectRetryMs;
    final ok = await _wifiDirect.start();
    debugPrint('MeshTransport: Wi-Fi Direct start → $ok '
        '(${_wifiDirect.status})');
    _emitChanges(force: true);
  }

  /// Send on every live radio. Throws when no device is reachable so the
  /// controller can queue the frame for store-and-forward delivery.
  @override
  Future<int> send(Uint8List frame) async {
    // Mark locally originated frames so the mesh does not re-deliver our own
    // packet when it echoes back through a peer.
    _ble.markOriginated(frame);
    _remember(frame);

    var fanout = 0;
    final failures = <String>[];

    if (_ble.isRunning) {
      try {
        fanout += await _ble.send(frame);
      } catch (e) {
        failures.add('BLE: $e');
      }
    }

    if (_wifiDirect.isRunning) {
      try {
        fanout += await _wifiDirect.send(frame);
      } catch (e) {
        failures.add('Wi-Fi Direct: $e');
      }
    }

    if (fanout > 0) return fanout;

    if (!stats.anyRunning) {
      throw StateError('No radio is running — Bluetooth and Wi-Fi Direct are '
          'both unavailable');
    }
    throw StateError(failures.isEmpty
        ? 'No peer in range yet'
        : failures.join(' · '));
  }

  void _onInbound(Uint8List frame) {
    if (frame.length < 8) return;
    if (!_isNewFrame(frame)) return;
    if (!_controller.isClosed) _controller.add(frame);
  }

  /// Cross-link dedup — the same frame can arrive over BLE *and* Wi-Fi Direct,
  /// and relays can echo it back. Each sequence ID is admitted once.
  bool _isNewFrame(Uint8List frame) {
    if (frame[0] != 0x49 || frame[1] != 0x54) return false;
    final id = ByteData.sublistView(frame).getUint32(4, Endian.big);
    final now = DateTime.now().millisecondsSinceEpoch;
    if (_seen.containsKey(id)) return false;
    if (_seen.length > 512) {
      _seen.removeWhere((_, t) => now - t > _dedupTtlMs);
      if (_seen.length > 512) _seen.clear();
    }
    _seen[id] = now;
    return true;
  }

  void _remember(Uint8List frame) {
    if (frame.length < 8) return;
    final id = ByteData.sublistView(frame).getUint32(4, Endian.big);
    _seen[id] = DateTime.now().millisecondsSinceEpoch;
  }

  void _emitChanges({bool force = false}) {
    final signature = stats.signature;
    if (!force && signature == _lastSignature) return;
    _lastSignature = signature;
    if (!_linkChanges.isClosed) _linkChanges.add(null);
  }

  /// Tear down both radios.
  @override
  Future<void> disconnect() async {
    _shouldRun = false;
    _pollTimer?.cancel();
    _healthTimer?.cancel();
    _pollTimer = null;
    _healthTimer = null;

    await _bleSub?.cancel();
    await _wifiSub?.cancel();
    _bleSub = null;
    _wifiSub = null;

    await _wifiDirect.stop();
    await _ble.stop();
    _nextWifiDirectAttemptMs = 0;

    if (!_linkChanges.isClosed) _linkChanges.add(null);
  }

  Future<void> dispose() async {
    await disconnect();
    await _wifiDirect.dispose();
    await _controller.close();
    await _linkChanges.close();
  }
}
