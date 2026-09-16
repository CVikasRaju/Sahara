import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Bridge to the native emergency layer (`iTantraChannels.kt`).
///
/// The SOS feature has two halves:
///
///  * **Dart** decides *when* an SOS happened (a packet arrived, or the user
///    pressed the button) and drives the UI.
///  * **Native** makes it *impossible to miss*: a loud alarm on the alarm
///    stream, a repeating vibration, a full-screen notification, and — via
///    [startStandby] — a foreground service that keeps the process (and this
///    Flutter isolate) alive after the app is swiped off the recents list.
///
/// Splitting it this way is deliberate: the audible alert must not depend on
/// the widget tree being mounted, because the whole point is to reach a phone
/// whose owner is not looking at the app.
///
/// Every method degrades to a safe no-op when the platform side is missing
/// (tests, desktop), so callers never have to guard.
class EmergencyService {
  EmergencyService._();

  static const MethodChannel _channel = MethodChannel('itantra/sos_service');

  static bool get _supported => Platform.isAndroid;

  /// Ask Android to keep iTantra running as a foreground service.
  ///
  /// Returns `true` when standby is active. Requires the Bluetooth (or Wi-Fi)
  /// runtime permission on Android 14+, because the service uses the
  /// `connectedDevice` foreground-service type.
  static Future<bool> startStandby() async {
    if (!_supported) return false;
    try {
      return await _channel.invokeMethod<bool>('startStandby') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException catch (e) {
      debugPrint('SOS: startStandby failed: ${e.message}');
      return false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> stopStandby() async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<bool>('stopStandby');
    } catch (_) {
      // Nothing to do.
    }
  }

  static Future<bool> isStandbyRunning() async {
    if (!_supported) return false;
    try {
      return await _channel.invokeMethod<bool>('isStandbyRunning') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Sound the loud alarm and post the full-screen SOS notification.
  ///
  /// [text] is the message to show; [from] is a sender label.
  static Future<void> raiseAlarm({String? text, String? from}) async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<bool>('raiseAlarm', {
        'text': text,
        'from': from,
      });
    } catch (e) {
      debugPrint('SOS: raiseAlarm failed: $e');
    }
  }

  /// Silence the alarm (after the user dismisses it, or on auto-clear).
  static Future<void> clearAlarm() async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<bool>('clearAlarm');
    } catch (_) {
      // Nothing to do.
    }
  }

  /// Whether the user granted "Do Not Disturb access".
  ///
  /// Without it an SOS still sounds (alarm-stream audio is exempt from DND),
  /// but the *spoken* message uses the media stream, which DND can mute.
  /// With it, DND is lifted for the duration of the alert and restored after.
  static Future<bool> hasDndAccess() async {
    if (!_supported) return false;
    try {
      return await _channel.invokeMethod<bool>('hasDndAccess') ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> openDndSettings() async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<bool>('openDndSettings');
    } catch (_) {
      // Nothing to do.
    }
  }

  static Future<void> openNotificationSettings() async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<bool>('openNotificationSettings');
    } catch (_) {
      // Nothing to do.
    }
  }

  /// Whether the app is exempt from battery optimisation.
  ///
  /// Not exempt means aggressive OEM battery savers (Xiaomi, Oppo, Vivo,
  /// Samsung) may kill standby, and with it SOS reception.
  static Future<bool> isIgnoringBatteryOptimizations() async {
    if (!_supported) return true;
    try {
      return await _channel.invokeMethod<bool>(
            'isIgnoringBatteryOptimizations',
          ) ??
          false;
    } catch (_) {
      return true;
    }
  }

  static Future<void> openBatterySettings() async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<bool>('openBatterySettings');
    } catch (_) {
      // Nothing to do.
    }
  }
}
