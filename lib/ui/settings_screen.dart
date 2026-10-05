import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../core/theme.dart';
import '../ml/ibfs.dart' show kMaxSenderNameChars;
import '../state/app_settings.dart';
import '../state/transceiver_controller.dart';

/// Settings screen.
///
/// Every value here is persisted through [AppSettings] (SharedPreferences), so
/// it survives a restart — including a restart triggered by the OS while the
/// app is running as a background standby service.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _name;
  late final TextEditingController _blood;
  late final TextEditingController _conditions;
  late final TextEditingController _contacts;

  @override
  void initState() {
    super.initState();
    final s = context.read<AppSettings>();
    _name = TextEditingController(text: s.username);
    _blood = TextEditingController(text: s.bloodGroup);
    _conditions = TextEditingController(text: s.conditions);
    _contacts = TextEditingController(text: s.emergencyContacts);
  }

  @override
  void dispose() {
    _name.dispose();
    _blood.dispose();
    _conditions.dispose();
    _contacts.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<AppSettings>();
    final ctrl = context.watch<TransceiverController>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
        children: [
          // ── Identity ──────────────────────────────────────────
          _Section(
            title: 'Identity',
            subtitle: 'Transmitted with every message so receivers know '
                'who is speaking.',
            children: [
              TextField(
                controller: _name,
                maxLength: kMaxSenderNameChars,
                textCapitalization: TextCapitalization.words,
                inputFormatters: [
                  LengthLimitingTextInputFormatter(kMaxSenderNameChars),
                  FilteringTextInputFormatter.deny(RegExp(r'[\n\r\t]')),
                ],
                style: const TextStyle(
                  fontSize: 14,
                  color: iTantraTheme.textPrimary,
                ),
                decoration: _fieldDecoration(
                  label: 'Username',
                  hint: 'e.g. Vikas',
                  counter: '${_name.text.runes.length}/'
                      '$kMaxSenderNameChars · short names save packet space',
                ),
                onChanged: (v) {
                  settings.username = v;
                  setState(() {});
                },
              ),
              const SizedBox(height: 4),
              Text(
                settings.username.isEmpty
                    ? 'Not set — receivers will see messages without a name.'
                    : 'Receivers will see "[${settings.username}] …"',
                style: TextStyle(
                  fontSize: 11,
                  color: settings.username.isEmpty
                      ? iTantraTheme.textMuted
                      : iTantraTheme.success,
                ),
              ),
            ],
          ),

          // ── Operation mode & role ─────────────────────────────
          _Section(
            title: 'Operation mode',
            subtitle: 'How the microphone is driven.',
            children: [
              _ChoiceRow<OperationMode>(
                value: settings.operationMode,
                options: const {
                  OperationMode.walkieTalkie: 'Walkie-Talkie (PTT)',
                  OperationMode.phone: 'Phone (hands-free)',
                },
                onChanged: (m) => settings.operationMode = m,
              ),
              const SizedBox(height: 8),
              Text(
                settings.isHandsFree
                    ? 'Hands-free: the mic stays open. Silero VAD closes a '
                        'sentence after 3.0 s of silence and sends it with no '
                        'button press.'
                    : 'Push-to-talk: hold the button to speak, release to send. '
                        'A quick tap latches recording until the next tap.',
                style: const TextStyle(
                  fontSize: 11,
                  color: iTantraTheme.textSecondary,
                  height: 1.35,
                ),
              ),
            ],
          ),

          _Section(
            title: 'Device role',
            subtitle: 'For a two-phone evaluation: set one phone to STT Mode '
                'and the other to TTS Mode to verify the full loop.',
            children: [
              _ChoiceRow<AppRole>(
                value: settings.role,
                options: const {
                  AppRole.transceiver: 'Transceiver',
                  AppRole.sttOnly: 'STT (sender)',
                  AppRole.ttsOnly: 'TTS (receiver)',
                },
                onChanged: (r) => settings.role = r,
              ),
              const SizedBox(height: 8),
              _RoleExplainer(role: settings.role),
            ],
          ),

          // ── Audio ─────────────────────────────────────────────
          _Section(
            title: 'Audio',
            subtitle: 'Playback speed for both the neural and platform voices.',
            children: [
              Row(
                children: [
                  const Icon(Icons.speed, size: 18,
                      color: iTantraTheme.saffron),
                  const SizedBox(width: 8),
                  Text(
                    '${settings.speechRate.toStringAsFixed(2)}×',
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: iTantraTheme.textPrimary,
                    ),
                  ),
                ],
              ),
              Slider(
                value: settings.speechRate,
                min: AppSettings.minSpeechRate,
                max: AppSettings.maxSpeechRate,
                divisions: 20,
                activeColor: iTantraTheme.saffron,
                inactiveColor: iTantraTheme.surfaceLight,
                onChanged: (v) => settings.speechRate = v,
              ),
              const Text(
                'Lower it if the neural voice sounds rushed, raise it if '
                'the voice is too slow to follow.',
                style: TextStyle(
                  fontSize: 11,
                  color: iTantraTheme.textMuted,
                ),
              ),
            ],
          ),

          // ── Emergency ─────────────────────────────────────────
          _Section(
            title: 'Emergency',
            subtitle: 'Pre-set telemetry is appended to every SOS you send.',
            children: [
              _SwitchRow(
                title: 'Hardware SOS trigger',
                subtitle: 'Hold a volume key to fire an SOS without looking '
                    'at the screen. Works while iTantra is running, including '
                    'in the background.',
                value: settings.silentSosEnabled,
                onChanged: (v) => settings.silentSosEnabled = v,
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _blood,
                style: const TextStyle(
                  fontSize: 14,
                  color: iTantraTheme.textPrimary,
                ),
                decoration: _fieldDecoration(
                  label: 'Blood group',
                  hint: 'e.g. O+',
                ),
                onChanged: (v) => settings.bloodGroup = v,
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _conditions,
                maxLines: 2,
                style: const TextStyle(
                  fontSize: 14,
                  color: iTantraTheme.textPrimary,
                ),
                decoration: _fieldDecoration(
                  label: 'Pre-existing conditions',
                  hint: 'e.g. asthma, diabetic',
                ),
                onChanged: (v) => settings.conditions = v,
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _contacts,
                style: const TextStyle(
                  fontSize: 14,
                  color: iTantraTheme.textPrimary,
                ),
                decoration: _fieldDecoration(
                  label: 'Emergency contact',
                  hint: 'e.g. 98765 43210',
                ),
                onChanged: (v) => settings.emergencyContacts = v,
              ),
              const SizedBox(height: 10),
              _EmergencyStatus(ctrl: ctrl),
            ],
          ),

          // ── Storage ───────────────────────────────────────────
          _Section(
            title: 'Storage & rescue log',
            subtitle: 'Downloaded AI models are kept — re-downloading a '
                '150 MB speech model offline is not possible.',
            children: [
              _ActionRow(
                icon: Icons.cleaning_services,
                label: 'Clear cached audio & message log',
                detail: 'Removes cached speech clips, the packet log and any '
                    'undelivered queued messages.',
                onTap: () => _clear(context, ctrl, const {}),
              ),
              _ActionRow(
                icon: Icons.memory,
                label: 'Delete downloaded speech models',
                detail: 'Last resort. Speech and voice models re-download on '
                    'next use — only do this if you are low on storage and '
                    'have connectivity.',
                onTap: () => _confirmDeleteSpeechModels(context, ctrl),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _confirmDeleteSpeechModels(
    BuildContext context,
    TransceiverController ctrl,
  ) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: iTantraTheme.surface,
        title: const Text('Delete speech models?'),
        content: const Text(
          'The offline speech-recognition and neural voice models will be '
          'deleted. Until they are downloaded again, transcription falls back '
          'to nothing and the voice falls back to the phone synthesizer.',
          style: TextStyle(fontSize: 13, height: 1.4),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('CANCEL'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            style: ElevatedButton.styleFrom(
              backgroundColor: iTantraTheme.danger,
              foregroundColor: Colors.white,
            ),
            child: const Text('DELETE'),
          ),
        ],
      ),
    );
    if (ok == true && context.mounted) {
      await _clear(
        context,
        ctrl,
        const {_ClearOption.speechModels},
      );
    }
  }

  Future<void> _clear(
    BuildContext context,
    TransceiverController ctrl,
    Set<_ClearOption> options,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final report = await ctrl.clearCaches(
      dropTranslationModels:
          options.contains(_ClearOption.translationModels),
      dropSpeechModels: options.contains(_ClearOption.speechModels),
    );
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          report.clipCount == 0 && report.logEntries == 0
              ? 'Nothing to clear'
              : 'Cleared ${report.clipCount} cached '
                  'clip${report.clipCount == 1 ? '' : 's'} '
                  '(${report.prettySize}) and ${report.logEntries} '
                  'log entr${report.logEntries == 1 ? 'y' : 'ies'}',
        ),
        duration: const Duration(seconds: 3),
      ),
    );
  }
}

enum _ClearOption { translationModels, speechModels }

// ── Building blocks ──────────────────────────────────────────────

InputDecoration _fieldDecoration({
  required String label,
  String? hint,
  String? counter,
}) {
  return InputDecoration(
    labelText: label,
    hintText: hint,
    counterText: counter,
    labelStyle: const TextStyle(fontSize: 13, color: iTantraTheme.textSecondary),
    hintStyle: const TextStyle(fontSize: 13, color: iTantraTheme.textMuted),
    counterStyle: const TextStyle(fontSize: 10, color: iTantraTheme.textMuted),
    isDense: true,
    filled: true,
    fillColor: iTantraTheme.surfaceLight,
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(8),
      borderSide: const BorderSide(color: iTantraTheme.border),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(8),
      borderSide: const BorderSide(color: iTantraTheme.border),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(8),
      borderSide: const BorderSide(color: iTantraTheme.saffron),
    ),
  );
}

class _Section extends StatelessWidget {
  const _Section({
    required this.title,
    required this.children,
    this.subtitle,
  });

  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: iTantraTheme.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: iTantraTheme.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title.toUpperCase(),
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.2,
              color: iTantraTheme.saffron,
            ),
          ),
          if (subtitle != null) ...[
            const SizedBox(height: 4),
            Text(
              subtitle!,
              style: const TextStyle(
                fontSize: 11,
                color: iTantraTheme.textMuted,
                height: 1.35,
              ),
            ),
          ],
          const SizedBox(height: 12),
          ...children,
        ],
      ),
    );
  }
}

/// Segmented choice row. Hand-built rather than [SegmentedButton] so it
/// matches the existing dark theme exactly.
class _ChoiceRow<T> extends StatelessWidget {
  const _ChoiceRow({
    required this.value,
    required this.options,
    required this.onChanged,
  });

  final T value;
  final Map<T, String> options;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: options.entries.map((e) {
        final selected = e.key == value;
        return Expanded(
          child: Padding(
            padding: const EdgeInsets.only(right: 6),
            child: InkWell(
              onTap: () => onChanged(e.key),
              borderRadius: BorderRadius.circular(8),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 9),
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: selected
                      ? iTantraTheme.saffron.withValues(alpha: 0.18)
                      : iTantraTheme.surfaceLight,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: selected
                        ? iTantraTheme.saffron
                        : iTantraTheme.border,
                  ),
                ),
                child: Text(
                  e.value,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: selected
                        ? iTantraTheme.saffron
                        : iTantraTheme.textSecondary,
                  ),
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: iTantraTheme.textPrimary,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                subtitle,
                style: const TextStyle(
                  fontSize: 11,
                  color: iTantraTheme.textMuted,
                  height: 1.3,
                ),
              ),
            ],
          ),
        ),
        Switch(
          value: value,
          onChanged: onChanged,
          activeThumbColor: iTantraTheme.saffron,
        ),
      ],
    );
  }
}

class _ActionRow extends StatelessWidget {
  const _ActionRow({
    required this.icon,
    required this.label,
    required this.detail,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final String detail;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: iTantraTheme.saffron),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: iTantraTheme.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    detail,
                    style: const TextStyle(
                      fontSize: 11,
                      color: iTantraTheme.textMuted,
                      height: 1.3,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right,
                size: 18, color: iTantraTheme.textMuted),
          ],
        ),
      ),
    );
  }
}

class _RoleExplainer extends StatelessWidget {
  const _RoleExplainer({required this.role});
  final AppRole role;

  @override
  Widget build(BuildContext context) {
    final text = switch (role) {
      AppRole.transceiver =>
        'Sends and receives. The normal operating mode.',
      AppRole.sttOnly =>
        'Sender only: microphone, live transcript and latency benchmarks '
            '(STT ms, RTF). Incoming audio is logged but never played.',
      AppRole.ttsOnly =>
        'Receiver only: mesh reception and loud voice playback. '
            'The microphone is disabled.',
    };
    return Text(
      text,
      style: const TextStyle(
        fontSize: 11,
        color: iTantraTheme.textSecondary,
        height: 1.35,
      ),
    );
  }
}

class _EmergencyStatus extends StatelessWidget {
  const _EmergencyStatus({required this.ctrl});
  final TransceiverController ctrl;

  @override
  Widget build(BuildContext context) {
    Widget chip(String label, bool ok, VoidCallback? onTap) {
      final color = ok ? iTantraTheme.success : iTantraTheme.saffron;
      return InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: color.withValues(alpha: 0.4)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(ok ? Icons.check_circle : Icons.error_outline,
                  size: 12, color: color),
              const SizedBox(width: 4),
              Text(
                label,
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w600,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      );
    }

    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        chip('Standby', ctrl.standbyActive, ctrl.openBatterySettings),
        chip('DND access', ctrl.dndAccess, ctrl.openDndSettings),
        chip('Battery exempt', ctrl.batteryExempt, ctrl.openBatterySettings),
      ],
    );
  }
}
