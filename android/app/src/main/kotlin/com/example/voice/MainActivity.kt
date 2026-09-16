package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugins.GeneratedPluginRegistrant

/**
 * iTantra's single Activity.
 *
 * The Activity attaches to a **cached** Flutter engine and refuses to destroy
 * it with the host. That is what makes "SOS reaches a phone whose app is
 * closed" work: when the user swipes iTantra off the recents list, the Activity
 * dies but the Dart isolate keeps running, so the BLE / Wi-Fi Direct mesh stays
 * up. [SosService] holds the process at foreground priority so Android does not
 * reclaim it in the meantime.
 *
 * All native channels are registered on the engine in
 * [iTantraChannels.register], before the Dart entrypoint starts, so they are
 * also available when no Activity is attached.
 */
class MainActivity : FlutterActivity() {

    companion object {
        /** Intent extra set when an SOS full-screen notification opens us. */
        const val EXTRA_SOS_ALERT = "itantra_sos_alert"

        /**
         * Cache key for the shared engine. Scoped to this process, so a fresh
         * install or process restart starts cleanly.
         */
        private const val ENGINE_ID = "itantra_main_engine"
    }

    /**
     * Provide (creating once) the cached engine.
     *
     * Registration order matters: plugins and native channels are wired up
     * *before* the Dart entrypoint runs, so Dart can never observe a missing
     * platform channel.
     */
    override fun provideFlutterEngine(context: Context): FlutterEngine {
        val cache = FlutterEngineCache.getInstance()
        cache.get(ENGINE_ID)?.let { return it }

        val engine = FlutterEngine(applicationContext)
        GeneratedPluginRegistrant.registerWith(engine)
        iTantraChannels.register(engine, applicationContext)
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault(),
        )
        cache.put(ENGINE_ID, engine)
        return engine
    }

    /**
     * Keep the engine alive across Activity destruction. This is the whole
     * mechanism behind receiving SOS signals while the app appears closed.
     */
    override fun shouldDestroyEngineWithHost(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyAlerts.ensureChannels(applicationContext)

        // When an SOS full-screen notification launches us, make sure the alarm
        // UI is visible over the lock screen and the display wakes up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    /** Whether this launch was triggered by an SOS alert. */
    fun launchedFromSos(): Boolean =
        intent?.getBooleanExtra(EXTRA_SOS_ALERT, false) == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
