package com.itantra.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.itantra.ml.SherpaSttEngine
import com.itantra.ml.SherpaTtsEngine
import com.itantra.ml.SherpaVadEngine
import com.itantra.ml.SttEngine
import com.itantra.ml.TtsEngine
import com.itantra.ml.VadEngine
import com.itantra.network.BluetoothTransport
import com.itantra.network.FrameCorruptException
import com.itantra.network.ItantraPacket
import com.itantra.network.Language
import com.itantra.network.MessageQueue
import com.itantra.network.PacketType
import com.itantra.network.Priority
import com.itantra.network.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the long-lived pieces: mic capture, VAD, STT/TTS
 * engines, the Bluetooth transport, and the store-and-forward queue. The UI
 * (MainActivity / TransceiverViewModel) binds to this and observes state via [state]
 * (a StateFlow) through the [LocalBinder].
 *
 * PIPELINE SHAPE (streaming, per docs/ARCHITECTURE.md §2.1):
 *
 *   AudioRecord (16kHz/16-bit mono)
 *     → [pcmChannel] (Channel<ShortArray>, 30ms = 480-sample frames)
 *       → VAD consumer coroutine: vadEngine.isSpeech() per frame
 *         → speech frames accumulated into utterance buffer
 *         → silence > 600ms (20 frames × 30ms) = utterance boundary
 *           → sttEngine.transcribe(utteranceAudio)
 *             → ProtocolCodec.encode() → BluetoothTransport.send() / MessageQueue.enqueue()
 */
class TransceiverService : Service() {

    private val tag = "TransceiverService"

    // ── Binder for UI binding ──────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        /** Observable transceiver state — collect in a ViewModel / composable. */
        val state: StateFlow<TransceiverState> get() = this@TransceiverService.state

        /** The currently selected language for STT/TTS. */
        val activeLanguage: StateFlow<Language> get() = this@TransceiverService._activeLanguage.asStateFlow()

        /** Call on PTT press. Transitions IDLE → RECORDING and starts mic capture. */
        fun startRecording() = this@TransceiverService.startRecording()

        /** Call on PTT release. Stops mic capture; VAD pipeline processes remaining buffer. */
        fun stopRecording() = this@TransceiverService.stopRecording()

        /** Switch active language — follows single-language-resident policy (ARCHITECTURE.md §2.2). */
        fun switchLanguage(language: Language) = this@TransceiverService.switchLanguage(language)

        /** Enable transceiver mode: TRANSCEIVER_OFF → IDLE, starts VAD pipeline and Bluetooth listening. */
        fun enableTransceiver() = this@TransceiverService.enableTransceiver()

        /** Disable transceiver mode: → TRANSCEIVER_OFF, stops everything. */
        fun disableTransceiver() = this@TransceiverService.disableTransceiver()
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ── Engines (Sherpa-ONNX backed — see ml/Engines.kt) ─────────────────
    // Lazy initialization defers native model loading until enableTransceiver()
    // is called, respecting the RAM footprint budget.

    private val vadEngine: VadEngine by lazy { SherpaVadEngine(this) }
    private val sttEngine: SttEngine by lazy { SherpaSttEngine(this) }
    private val ttsEngine: TtsEngine by lazy { SherpaTtsEngine(this) }
    private val messageQueue = MessageQueue()

    // ── Bluetooth Transport ────────────────────────────────────────────────

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(BluetoothManager::class.java)?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    private val bluetoothTransport: BluetoothTransport by lazy {
        BluetoothTransport(
            adapter = bluetoothAdapter,
            onFrameReceived = { rawFrame -> onFrameReceived(rawFrame) },
            onConnectionStateChanged = { connected -> handleConnectionStateChanged(connected) }
        )
    }

    // ── Coroutine infrastructure ───────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The central streaming spine: mic capture coroutine produces 30ms PCM frames
     * into this channel, the VAD consumer coroutine reads from it.
     */
    private val pcmChannel = Channel<ShortArray>(capacity = Channel.BUFFERED)

    private var micCaptureJob: Job? = null
    private var vadConsumerJob: Job? = null

    // ── State management ───────────────────────────────────────────────────

    private val _state = MutableStateFlow<TransceiverState>(TransceiverState.TransceiverOff)
    val state: StateFlow<TransceiverState> = _state.asStateFlow()

    private val _activeLanguage = MutableStateFlow(Language.HINDI)
    val activeLanguage: Language get() = _activeLanguage.value

    /**
     * Guarded state transition — rejects illegal transitions per the state machine
     * in docs/ARCHITECTURE.md §3, implemented in [TransceiverStateMachine].
     */
    private fun transitionTo(newState: TransceiverState) {
        val current = _state.value
        if (current == newState) return
        if (!TransceiverStateMachine.canTransition(current, newState)) {
            Log.w(tag, "Illegal transition ignored: ${current::class.simpleName} → ${newState::class.simpleName}")
            return
        }
        _state.value = newState
    }

    // ── Foreground notification ────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "itantra_transceiver"
        private const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE_HZ = 16000
        /** 30ms @ 16kHz = 480 samples per frame, per docs/ARCHITECTURE.md §2.1. */
        const val SAMPLES_PER_FRAME = 480
        /** Silence threshold: 600ms ÷ 30ms/frame = 20 frames. */
        const val SILENCE_THRESHOLD_FRAMES = 20
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("iTantra Transceiver")
                .setContentText("Listening for peer messages")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("iTantra Transceiver")
                .setContentText("Listening for peer messages")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Transceiver Active",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while the iTantra transceiver is active"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    // ── Transceiver lifecycle ──────────────────────────────────────────────

    private fun enableTransceiver() {
        transitionTo(TransceiverState.Idle)
        sttEngine.loadLanguage(activeLanguage)
        ttsEngine.loadLanguage(activeLanguage)
        startVadPipeline()
        bluetoothTransport.startListening(serviceScope)
    }

    private fun disableTransceiver() {
        stopMicCapture()
        stopVadPipeline()
        bluetoothTransport.stop()
        sttEngine.unloadCurrentLanguage()
        ttsEngine.unloadCurrentLanguage()
        _state.value = TransceiverState.TransceiverOff
    }

    private fun switchLanguage(language: Language) {
        if (language == activeLanguage) return
        _activeLanguage.value = language
        sttEngine.unloadCurrentLanguage()
        ttsEngine.unloadCurrentLanguage()
        sttEngine.loadLanguage(language)
        ttsEngine.loadLanguage(language)
    }

    // ── PTT recording control ──────────────────────────────────────────────

    private fun startRecording() {
        if (_state.value !is TransceiverState.Idle) return
        transitionTo(TransceiverState.Recording)
        startMicCapture()
    }

    private fun stopRecording() {
        if (_state.value !is TransceiverState.Recording) return
        stopMicCapture()
        serviceScope.launch {
            pcmChannel.send(ShortArray(0)) // sentinel: PTT released
        }
    }

    // ── Mic capture coroutine (producer) ───────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startMicCapture() {
        micCaptureJob?.cancel()
        micCaptureJob = serviceScope.launch(Dispatchers.IO) {
            var audioRecord: AudioRecord? = null
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSizeInBytes = maxOf(minBufferSize, SAMPLES_PER_FRAME * 2 * 2)
                
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes
                )

                if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.startRecording()
                    while (isActive) {
                        val frame = ShortArray(SAMPLES_PER_FRAME)
                        val readSamples = audioRecord.read(frame, 0, SAMPLES_PER_FRAME)
                        if (readSamples > 0) {
                            pcmChannel.send(if (readSamples == SAMPLES_PER_FRAME) frame else frame.copyOf(readSamples))
                        } else if (readSamples < 0) {
                            Log.e(tag, "AudioRecord read error: $readSamples")
                            break
                        }
                    }
                } else {
                    Log.w(tag, "AudioRecord not initialized, falling back to simulated frames")
                    while (isActive) {
                        delay(30)
                        pcmChannel.send(ShortArray(SAMPLES_PER_FRAME))
                    }
                }
            } catch (e: SecurityException) {
                Log.e(tag, "Missing RECORD_AUDIO permission: ${e.message}")
            } catch (e: Exception) {
                Log.e(tag, "Error during mic capture: ${e.message}")
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopMicCapture() {
        micCaptureJob?.cancel()
        micCaptureJob = null
    }

    // ── VAD consumer coroutine ─────────────────────────────────────────────

    private fun startVadPipeline() {
        vadConsumerJob?.cancel()
        vadConsumerJob = serviceScope.launch {
            val utteranceBuffer = mutableListOf<ShortArray>()
            var silentFrameCount = 0

            for (frame in pcmChannel) {
                if (frame.isEmpty()) {
                    if (utteranceBuffer.isNotEmpty()) {
                        processUtterance(utteranceBuffer.flattenToShortArray())
                        utteranceBuffer.clear()
                    }
                    silentFrameCount = 0
                    continue
                }

                val isSpeech = vadEngine.isSpeech(frame)

                if (isSpeech) {
                    utteranceBuffer.add(frame.copyOf())
                    silentFrameCount = 0
                } else {
                    silentFrameCount++
                    if (silentFrameCount >= SILENCE_THRESHOLD_FRAMES && utteranceBuffer.isNotEmpty()) {
                        processUtterance(utteranceBuffer.flattenToShortArray())
                        utteranceBuffer.clear()
                        silentFrameCount = 0
                    }
                }
            }
        }
    }

    private fun stopVadPipeline() {
        vadConsumerJob?.cancel()
        vadConsumerJob = null
    }

    private fun processUtterance(utteranceAudio: ShortArray) {
        transitionTo(TransceiverState.Processing(activeLanguage.code))

        val result = sttEngine.transcribe(utteranceAudio)
        if (result == null) {
            transitionTo(TransceiverState.Idle)
            return
        }

        transitionTo(TransceiverState.Transmitting(result.text))

        val packet = ItantraPacket(
            type = PacketType.PTT_VOICE_NOTE,
            priority = Priority.ROUTINE,
            language = activeLanguage,
            sequenceId = messageQueue.nextSequenceId(),
            text = result.text,
        )

        val frame = ProtocolCodec.encode(packet)
        val sent = bluetoothTransport.send(frame)
        if (!sent) {
            messageQueue.enqueue(packet)
            Log.d(tag, "Peer offline, queued packet seq=${packet.sequenceId}")
        }

        transitionTo(TransceiverState.Idle)
    }

    // ── Bluetooth Connection Handling ──────────────────────────────────────

    private fun handleConnectionStateChanged(connected: Boolean) {
        Log.d(tag, "Bluetooth connection state changed: connected=$connected")
        if (connected) {
            serviceScope.launch(Dispatchers.IO) {
                val frames = messageQueue.drainAsFrames()
                if (frames.isNotEmpty()) {
                    Log.d(tag, "Draining ${frames.size} stored frames to connected peer")
                    for (frame in frames) {
                        val sent = bluetoothTransport.send(frame)
                        if (!sent) break
                    }
                }
            }
        }
    }

    // ── Receive path (inbound frames) ──────────────────────────────────────

    fun onFrameReceived(rawFrame: ByteArray) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val packet = ProtocolCodec.decode(rawFrame)
                val isEmergency = packet.priority == Priority.EMERGENCY

                transitionTo(TransceiverState.ReceivingPlayback(packet.text, isEmergency))

                val pcmAudio = ttsEngine.synthesize(packet.text)
                if (pcmAudio.isNotEmpty()) {
                    playAudio(pcmAudio, isEmergency)
                }

                transitionTo(TransceiverState.Idle)
            } catch (e: FrameCorruptException) {
                Log.w(tag, "Dropped corrupted frame: ${e.message}")
            } catch (e: Exception) {
                Log.e(tag, "Error handling received frame", e)
                transitionTo(TransceiverState.Idle)
            }
        }
    }

    private fun playAudio(pcmAudio: ShortArray, isEmergency: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var focusRequest: AudioFocusRequest? = null

        val usage = if (isEmergency) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        val contentType = AudioAttributes.CONTENT_TYPE_SPEECH

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeInBytes = maxOf(minBufSize, pcmAudio.size * 2)

        if (isEmergency && audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attributes)
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            track.write(pcmAudio, 0, pcmAudio.size)
            track.stop()
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack playback failed", e)
        } finally {
            try { track?.release() } catch (_: Exception) {}
            if (isEmergency && audioManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
            }
        }
    }

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onDestroy() {
        stopMicCapture()
        stopVadPipeline()
        bluetoothTransport.stop()
        pcmChannel.close()
        sttEngine.unloadCurrentLanguage()
        ttsEngine.unloadCurrentLanguage()
        // Release VAD native resources (SherpaVadEngine has its own release())
        (vadEngine as? SherpaVadEngine)?.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}

// ── Extension: flatten List<ShortArray> to a single ShortArray ─────────────

private fun List<ShortArray>.flattenToShortArray(): ShortArray {
    val totalSize = sumOf { it.size }
    val result = ShortArray(totalSize)
    var offset = 0
    for (chunk in this) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
