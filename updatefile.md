# iTantra Project Progress & AI Handover State

**Project:** iTantra (ISRO SIH26173)  
**Description:** A fully offline, peer-to-peer multilingual walkie-talkie Android application using Jetpack Compose, Kotlin Coroutines/Flows, offline AI (VAD, STT, TTS), and P2P Bluetooth RFCOMM / Wi-Fi Direct.  
**Last Updated:** August 28, 2026

---

## 1. Executive Summary

This file serves as a complete handover document for any developer or AI assistant continuing work on the **iTantra** codebase. All foundational architecture, binary framing, streaming audio pipeline, Bluetooth store-and-forward transport, UI/ViewModel layers, and sherpa-onnx ML engine integration have been implemented.

---

## 2. Completed Phases & Implementations

### Phase 1: Protocol Validation, State Machine & UI Setup
- **Network Framing (iBFS-v1)**:
  - `ProtocolCodec.kt` (`app/src/main/java/com/itantra/network/ProtocolCodec.kt`): Full byte-aligned implementation of the 14-byte overhead framing protocol (Magic `0x49 0x54`, 4-bit nibbles for Version/Type/Priority/Language, uint32 Sequence ID, uint16 Payload length, extended GPS/Source-lang flags, and CRC-16-CCITT).
  - `ProtocolCodecTest.kt` (`app/src/test/java/com/itantra/network/ProtocolCodecTest.kt`): 8 comprehensive unit tests covering round-trips, emergency priority, GPS payload, translation relay, CRC corruption, and frame truncation. **All 8 tests pass cleanly.**
- **UI Language Prioritization**:
  - `MainActivity.kt` (`app/src/main/java/com/itantra/ui/MainActivity.kt`): Defaulted `selectedLanguage` to `Language.HINDI` with `Language.KANNADA` as the secondary quick-select chip. The remaining 8 Indian languages are accessible via a deprioritized dropdown submenu.

---

### Phase 2: Streaming Pipeline, ViewModel, Audio I/O & Bluetooth Transport
- **Streaming Pipeline Core**:
  - `TransceiverService.kt` (`app/src/main/java/com/itantra/core/TransceiverService.kt`): Restructured from a one-shot skeleton into a real-time streaming pipeline using `Channel<ShortArray>(capacity = Channel.BUFFERED)`.
  - Continuous 30ms PCM frames (480 samples @ 16kHz) flow from mic capture through `vadEngine.isSpeech()`.
  - Utterance boundary detection: 600ms of continuous silence (`SILENCE_THRESHOLD_FRAMES = 20`) triggers STT transcription, binary encoding, and transmission.
- **Audio Capture & Playback**:
  - `startMicCapture()`: Configured Android `AudioRecord` (16kHz, Mono, 16-bit PCM, buffer calculated dynamically) reading 480-sample chunks directly with blocking read backpressure.
  - `onFrameReceived()`: Implemented `AudioTrack` playback for inbound synthesized audio. Supports emergency override (`USAGE_ALARM`, `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`) when `packet.priority == Priority.EMERGENCY`.
- **Bluetooth Transport & Message Queue**:
  - `BluetoothTransport.kt` (`app/src/main/java/com/itantra/network/BluetoothTransport.kt`): Implemented length-prefixed binary framing over RFCOMM sockets with exponential backoff reconnect logic (`1s -> 2s -> 4s -> ... -> 16s`).
  - `MessageQueue.kt` (`app/src/main/java/com/itantra/network/MessageQueue.kt`): Store-and-forward queue. Outbound packets queue if peer is offline and are automatically drained via `messageQueue.drainAsFrames()` upon connection. Sequence ID deduplication for mesh relay.
- **ViewModel & UI Reactive Wiring**:
  - `TransceiverViewModel.kt` (`app/src/main/java/com/itantra/ui/TransceiverViewModel.kt`): Manages `ServiceConnection` lifecycle with `TransceiverService` and exposes `StateFlow<TransceiverState>` and `StateFlow<Language>`.
  - `MainActivity.kt` (`app/src/main/java/com/itantra/ui/MainActivity.kt`): Dynamically binds to `TransceiverViewModel`. PTT button visually updates based on state:
    - `TransceiverOff`: "OFF"
    - `Idle`: "HOLD\nTO TALK" (Primary)
    - `Recording`: "RECORDING\nRELEASE TO SEND" (Red / Error)
    - `Processing`: "PROCESSING\n..." (Tertiary)
    - `Transmitting`: "SENDING\n..." (Secondary)
    - `ReceivingPlayback`: "RECEIVING\n..." (Alert / Primary Container)
    - `ConnectionLost`: "OFFLINE\nQUEUED"
  - Fixed compiler warnings (replaced deprecated `Divider` with `HorizontalDivider`, removed unused variables).

---

### Phase 3: Sherpa-ONNX ML Engine Integration
- **Dependency Added**:
  - `settings.gradle.kts`: Added JitPack repository (`https://jitpack.io`) for resolving sherpa-onnx.
  - `app/build.gradle.kts`: Added `com.github.k2-fsa:sherpa-onnx-android:1.13.6` replacing the previous TODO placeholder.
- **Real Neural Engine Implementations in `ml/Engines.kt`**:
  - `SherpaVadEngine`: Wraps Silero VAD via sherpa-onnx `Vad` class. Loads `silero_vad.onnx` from assets. Falls back to energy-based detection if model absent.
  - `SherpaSttEngine`: Wraps sherpa-onnx `OfflineRecognizer` with Whisper-style config. Loads language-specific encoder/decoder/tokens from `assets/models/stt/<lang>/`. Includes warm-up inference to avoid first-utterance latency spike. Returns null gracefully if model absent.
  - `SherpaTtsEngine`: Wraps sherpa-onnx `OfflineTts` with VITS model config. Loads from `assets/models/tts/<lang>/`. Includes Float→Short PCM conversion and linear-interpolation resampling (VITS 22050Hz → pipeline 16kHz). Returns silence fallback if model absent.
  - `assetExists()` utility: Safely checks if an asset file exists without throwing.
- **TransceiverService.kt Wiring**:
  - Replaced `MockVadEngine`, `MockSttEngine`, `MockTtsEngine` with `SherpaVadEngine`, `SherpaSttEngine`, `SherpaTtsEngine` using `by lazy` for deferred native model loading.
  - Added VAD native resource cleanup via `(vadEngine as? SherpaVadEngine)?.release()` in `onDestroy()`.
  - `switchLanguage()` already correctly follows Single-Language Resident Policy — unloads old language before loading new.
- **Asset Layout Documentation**: Created `app/src/main/assets/models/README.md` documenting exact file paths and naming conventions for model placement.

---

## 3. Current Repository Architecture & File Mapping

```
iTantra/
├── app/
│   ├── build.gradle.kts                        # App build config (Kotlin 1.9, Compose BOM 2024.06.00)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml             # Foreground service, Bluetooth, Audio & Location permissions
│       │   └── java/com/itantra/
│       │       ├── core/
│       │       │   ├── TransceiverService.kt   # Foreground service owning mic, VAD, STT/TTS, Bluetooth, StateFlow
│       │       │   └── TransceiverState.kt     # Sealed class state machine + transition validator
│       │       ├── ml/
│       │       │   └── Engines.kt              # VadEngine, SttEngine, TtsEngine interfaces, Mock & Sherpa implementations
│       │       ├── network/
│       │       │   ├── BluetoothTransport.kt   # RFCOMM socket transport with auto-reconnect backoff
│       │       │   ├── MessageQueue.kt         # Store-and-forward buffer & sequence dedup
│       │       │   └── ProtocolCodec.kt        # iBFS-v1 binary packet encoder/decoder & CRC16
│       │       └── ui/
│       │           ├── MainActivity.kt         # Jetpack Compose UI (PTT Hold button, Language chips)
│       │           └── TransceiverViewModel.kt # ViewModel managing ServiceConnection & StateFlows
│       └── test/java/com/itantra/network/
│           └── ProtocolCodecTest.kt            # 8 JUnit unit tests for network protocol
├── app/src/main/assets/models/
│   └── README.md                                # Required model file layout documentation
├── docs/
│   ├── ARCHITECTURE.md                         # Pipeline, single-language resident policy, state machine
│   └── NETWORK_PROTOCOL.md                     # iBFS-v1 binary packet layout spec
└── updatefile.md                               # This handover file
```

---

## 4. Verification & Build Status

- **Build Tool**: Gradle 8.14 (Wrapper configured with local cache)
- **JDK Requirement**: Java 17 (e.g. Android Studio JBR at `C:\Program Files\Android\Android Studio\jbr`)
- **Compilation**: Clean (`./gradlew compileDebugKotlin` passes with 0 errors and 0 warnings)
- **Unit Tests**: `./gradlew testDebugUnitTest` runs 8 tests in `ProtocolCodecTest`:
  - `basic round trip preserves all fields`: PASS
  - `emergency priority round trips correctly`: PASS
  - `extended payload with GPS round trips`: PASS
  - `extended payload with source language for translation relay round trips`: PASS
  - `corrupted magic bytes are rejected`: PASS
  - `corrupted payload byte fails CRC check`: PASS
  - `truncated frame is rejected`: PASS
  - `frame overhead matches spec — 14 bytes for empty text`: PASS

---

## 5. Next Planned Work: Phase 4 (Model Download, Wi-Fi Direct & End-to-End Testing)

Phase 3 (Sherpa-ONNX ML Integration) is **complete**. The next developer / AI model should execute **Phase 4**:

1. **Download & Place Real Model Files**:
   - Run `python scripts/fetch_models.py --lang hi,kn --output assets/models_raw` to download raw models.
   - Run `python scripts/quantize_models.py` to produce INT8 quantized ONNX files.
   - Place quantized files in `app/src/main/assets/models/` following the layout in `app/src/main/assets/models/README.md`.
   - Verify `tokens.txt` matches each model (see docs/ML_PIPELINE.md §6).
2. **Wi-Fi Direct Transport**:
   - Implement `WifiDirectTransport.kt` as an alternative to Bluetooth RFCOMM for higher bandwidth scenarios.
   - Add transport selection logic in `TransceiverService.kt`.
3. **End-to-End On-Device Testing**:
   - Deploy to a real Android device with model files.
   - Test full pipeline: PTT → VAD → STT → encode → transmit → receive → decode → TTS → playback.
   - Run `scripts/benchmark_latency.py` on target hardware to measure STT RTF, TTS RTF, WER, peak RAM, and cold-load time.
4. **Optional Differentiators** (see docs/ADDITIONAL_FEATURES.md):
   - Distress keyword detection.
   - GPS stamping.
   - Cross-language translation relay via IndicTrans2.
   - Adaptive fallback to raw audio when STT confidence is low.

---

## 6. How to Run & Test

To compile and run tests on Windows PowerShell:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest
```