import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa;

import 'core/theme.dart';
import 'ml/stt_engine.dart';
import 'ml/tts_engine.dart';
import 'net/mesh_transport.dart';
import 'state/app_settings.dart';
import 'state/battery_monitor.dart';
import 'state/transceiver_controller.dart';
import 'ui/home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // sherpa-onnx >= 1.13 requires the native C API bindings to be loaded
  // before creating ANY runtime object (recognizer, VAD, TTS). Without
  // this, every engine call throws 'Please initialize sherpa-onnx first'.
  sherpa.initBindings();

  // Preferences are loaded before the first frame so the UI never renders a
  // default (wrong) username, role or speech rate and then corrects itself.
  final settings = AppSettings();
  await settings.load();

  runApp(iTantraApp(settings: settings));
}

class iTantraApp extends StatelessWidget {
  const iTantraApp({super.key, required this.settings});

  /// App-wide preferences, shared by the controller and the Settings screen.
  final AppSettings settings;

  /// App-wide transport: BLE mesh + Wi-Fi Direct, aggregated and deduplicated.
  static final MeshTransport transport = MeshTransport();

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider<AppSettings>.value(value: settings),
        ChangeNotifierProvider(
          create: (_) {
            final controller = TransceiverController(
              stt: SttEngine(),
              tts: TtsEngine(),
              transport: transport,
              settings: settings,
            );
            controller.loadLog();
            return controller;
          },
        ),
        ChangeNotifierProvider(
          create: (_) {
            final monitor = BatteryMonitor();
            monitor.startMonitoring();
            return monitor;
          },
        ),
      ],
      child: MaterialApp(
        title: 'iTantra',
        debugShowCheckedModeBanner: false,
        theme: iTantraTheme.dark,
        home: const HomeScreen(),
      ),
    );
  }
}
