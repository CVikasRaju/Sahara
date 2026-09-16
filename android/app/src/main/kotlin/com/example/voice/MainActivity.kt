package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "itantra/audio_override"
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var wifiDirect: WifiDirectPlugin? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Second radio: Wi-Fi Direct group formation + TCP frame relay.
        // Registered before the Dart side asks for it, so `start` never races
        // the plugin registration.
        wifiDirect = WifiDirectPlugin(
            applicationContext,
            flutterEngine.dartExecutor.binaryMessenger,
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setMaxVolume" -> {
                        try {
                            val am = audioManager
                            if (am == null) {
                                result.error("NO_AM", "AudioManager unavailable", null)
                                return@setMethodCallHandler
                            }
                            // Seize transient audio focus so other apps duck.
                            requestAudioFocus(am)
                            // Force the media stream to maximum volume.
                            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR", e.message, null)
                        }
                    }
                    "restoreAudio" -> {
                        try {
                            abandonAudioFocus()
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR", e.message, null)
                        }
                    }
                    "extractAsset" -> {
                        val assetPath = call.argument<String>("assetPath")
                        val destPath = call.argument<String>("destPath")
                        if (assetPath == null || destPath == null) {
                            result.error("ARG_ERR", "Missing path arguments", null)
                            return@setMethodCallHandler
                        }
                        Thread {
                            try {
                                val destFile = java.io.File(destPath)
                                val tempFile = java.io.File("$destPath.tmp")
                                destFile.parentFile?.mkdirs()

                                var copied = false
                                val flutterAssetPath = if (assetPath.startsWith("flutter_assets/")) assetPath else "flutter_assets/$assetPath"

                                // Attempt 1: Try reading via AssetManager
                                try {
                                    assets.open(flutterAssetPath).use { input ->
                                        java.io.FileOutputStream(tempFile).use { output ->
                                            val buffer = ByteArray(65536)
                                            var bytesRead: Int
                                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                            }
                                            output.flush()
                                        }
                                    }
                                    if (tempFile.length() > 0) copied = true
                                } catch (_: Exception) {
                                    // AssetManager could not read or exceeded uncompress limit
                                }

                                // Attempt 2: If AssetManager failed, stream directly from the APK ZipFile
                                if (!copied) {
                                    val apkPath = applicationContext.applicationInfo.sourceDir
                                    java.util.zip.ZipFile(apkPath).use { zip ->
                                        val entry = zip.getEntry("assets/$flutterAssetPath")
                                            ?: zip.getEntry(flutterAssetPath)
                                            ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(assetPath) }

                                        if (entry != null) {
                                            zip.getInputStream(entry).use { input ->
                                                java.io.FileOutputStream(tempFile).use { output ->
                                                    val buffer = ByteArray(65536)
                                                    var bytesRead: Int
                                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                                        output.write(buffer, 0, bytesRead)
                                                    }
                                                    output.flush()
                                                }
                                            }
                                            if (tempFile.length() > 0) copied = true
                                        }
                                    }
                                }

                                if (copied && tempFile.length() > 0) {
                                    if (destFile.exists()) destFile.delete()
                                    tempFile.renameTo(destFile)
                                    runOnUiThread { result.success(true) }
                                } else {
                                    tempFile.delete()
                                    runOnUiThread { result.error("COPY_ERR", "Could not extract asset: $assetPath", null) }
                                }
                            } catch (e: Exception) {
                                runOnUiThread { result.error("COPY_ERR", e.message, null) }
                            }
                        }.start()
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun requestAudioFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        abandonAudioFocus()
        wifiDirect = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
