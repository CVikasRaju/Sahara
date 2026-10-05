package com.itantra.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * EmergencyAlarmTrigger manages overriding system audio constraints for emergency alarms.
 * It handles:
 * 1. Bypassing Do Not Disturb (DND) by forcing normal ringer mode and system interruption filters.
 * 2. Boosting alarm and music streams to maximum volume.
 * 3. Backing up and restoring user's original volume and ringer profiles.
 * 4. Generating offline siren warning alerts via AudioTrack.
 */
class EmergencyAlarmTrigger private constructor() {

    companion object {
        private const val TAG = "EmergencyAlarmTrigger"
        
        @Volatile
        private var instance: EmergencyAlarmTrigger? = null
        
        fun getInstance(): EmergencyAlarmTrigger {
            return instance ?: synchronized(this) {
                instance ?: EmergencyAlarmTrigger().also { instance = it }
            }
        }
    }

    private var originalRingerMode: Int? = null
    private var originalAlarmVolume: Int? = null
    private var originalMusicVolume: Int? = null
    private var originalInterruptionFilter: Int? = null
    
    private var sirenJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Starts the emergency audio session. Saves original ringer, volume, and filter levels,
     * then maximizes volume on STREAM_ALARM/STREAM_MUSIC and resets filters.
     */
    @Synchronized
    fun startEmergencySession(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        // Save original ringer mode and volumes
        if (originalRingerMode == null) {
            originalRingerMode = audioManager.ringerMode
        }
        if (originalAlarmVolume == null) {
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        if (originalMusicVolume == null) {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        // Save and override Interruption Filter (DND) if access is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null) {
            try {
                if (originalInterruptionFilter == null) {
                    originalInterruptionFilter = notificationManager.currentInterruptionFilter
                }
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read/write interruption filter (DND)", e)
            }
        }

        // Force ringer mode to normal
        try {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force ringer mode to normal (needs DND access permission)", e)
        }

        // Force volume to max
        try {
            val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, AudioManager.FLAG_PLAY_SOUND)
            
            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, AudioManager.FLAG_PLAY_SOUND)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set streams to maximum volume", e)
        }
        Log.i(TAG, "Emergency session started. DND overridden & volumes set to max.")
    }

    /**
     * Stops the emergency audio session. Restores original volume, ringer, and DND settings.
     */
    @Synchronized
    fun stopEmergencySession(context: Context) {
        stopSiren()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        if (audioManager != null) {
            // Restore volumes
            originalAlarmVolume?.let {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore alarm volume", e)
                }
            }
            originalMusicVolume?.let {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore music volume", e)
                }
            }
            // Restore ringer mode
            originalRingerMode?.let {
                try {
                    audioManager.ringerMode = it
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore ringer mode", e)
                }
            }
        }

        // Restore Interruption Filter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null) {
            originalInterruptionFilter?.let {
                try {
                    if (notificationManager.isNotificationPolicyAccessGranted) {
                        notificationManager.setInterruptionFilter(it)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore interruption filter", e)
                }
            }
        }

        // Reset saved states
        originalRingerMode = null
        originalAlarmVolume = null
        originalMusicVolume = null
        originalInterruptionFilter = null
        Log.i(TAG, "Emergency session stopped. Original audio states restored.")
    }

    /**
     * Alternating dual-tone siren played via AudioTrack on the ALARM stream.
     * Generates a 250ms siren at 800Hz followed by 250ms at 1000Hz repeatedly.
     */
    @Synchronized
    fun startSiren() {
        if (sirenJob != null) return
        
        sirenJob = scope.launch {
            val sampleRate = 16000
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
                
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                
                var toneToggle = false
                val frameCount = 4000 // 250ms at 16kHz
                val pcmData = ShortArray(frameCount)
                
                while (isActive) {
                    val freq = if (toneToggle) 800.0 else 1000.0
                    toneToggle = !toneToggle
                    
                    // Generate sine wave samples
                    for (i in 0 until frameCount) {
                        val angle = 2.0 * Math.PI * i * freq / sampleRate
                        pcmData[i] = (sin(angle) * Short.MAX_VALUE).toInt().toShort()
                    }
                    
                    // Write to AudioTrack
                    var written = 0
                    while (written < frameCount && isActive) {
                        val result = audioTrack?.write(pcmData, written, frameCount - written) ?: -1
                        if (result < 0) {
                            Log.e(TAG, "AudioTrack write error: $result")
                            break
                        }
                        written += result
                    }
                    delay(250)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error running siren playback", e)
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}
                audioTrack = null
            }
        }
        Log.i(TAG, "Siren started.")
    }

    /**
     * Cancels and stops the siren playback job.
     */
    @Synchronized
    fun stopSiren() {
        sirenJob?.cancel()
        sirenJob = null
        Log.i(TAG, "Siren stopped.")
    }
}
