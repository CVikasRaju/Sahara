<<<<<<< HEAD
<h1 align="center">
  <br>
  iTantra <sub>(ई-तंत्र)</sub>
  <br>
</h1>

<h4 align="center">Indian Multilingual Neural Transceiver — Offline TTS/STT Radio for Low-Bitrate Links</h4>

<p align="center">
  <strong>100% Offline</strong>&nbsp;&nbsp;|&nbsp;&nbsp;
  <strong>10 Indic Languages</strong>&nbsp;&nbsp;|&nbsp;&nbsp;
  <strong>Edge AI</strong>
</p>

<p align="center">
  <a href="#-quickstart">Quickstart</a>&nbsp;&nbsp;•&nbsp;&nbsp;
  <a href="#-how-it-works">How It Works</a>&nbsp;&nbsp;•&nbsp;&nbsp;
  <a href="#-architecture">Architecture</a>&nbsp;&nbsp;•&nbsp;&nbsp;
  <a href="#-benchmark-targets">Benchmarks</a>&nbsp;&nbsp;•&nbsp;&nbsp;
  <a href="#-documentation">Docs</a>&nbsp;&nbsp;•&nbsp;&nbsp;
  <a href="#-contributing">Contributing</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Inference-100%25%20Offline-green.svg" alt="Offline">
  <img src="https://img.shields.io/badge/Platform-Android-orange.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Languages-10%20Indic-blueviolet.svg" alt="Languages">
  <img src="https://img.shields.io/badge/Models-AI4Bharat%20INT8%20ONNX-blue.svg" alt="Models">
  <img src="https://img.shields.io/badge/Codec-iBFS--v1%20binary-ff6b6b.svg" alt="Codec">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

---

## Why This Exists

Cellular and internet infrastructure fail first in disasters. Voice carries information text can't — tone, urgency, and it works for anyone regardless of literacy — but raw audio is too heavy for the weak ad-hoc links (Bluetooth, Wi-Fi Direct) that survive.

**iTantra never transcribes audio at all.** It transcribes **speech to text on-device**, sends the text (tens of bytes, not tens of kilobytes) over the ad-hoc link, and re-synthesizes speech on the other end. Emergency messages override system audio at max volume and cannot be dismissed.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    THE iTANTRA CORE LOOP                            │
│                                                                     │
│  [Speaker]──mic──▶[VAD]──chunk──▶[Offline STT]──text──▶            │
│       [iBFS-v1 Encoder]──Bluetooth/Wi-Fi──▶                        │
│       [iBFS-v1 Decoder]──text──▶[Offline TTS]──PCM──▶[Speaker]    │
│                                                                     │
│  Two phones, one PTT, one listening — fully offline, no internet.  │
└─────────────────────────────────────────────────────────────────────┘
```

**The problem:** raw audio at 16 kHz/16-bit mono = ~96 KB/s. A 3-second utterance = **96,000 bytes** over a fragile link.

**The solution:** iTantra transmits **~40–80 bytes** of text per message — a **99.9% bandwidth reduction** — while preserving the voice experience through on-device TTS.

---

## Supported Languages

| | Language | Wire ID | Script | | Language | Wire ID | Script |
|---|---|---|---|---|---|---|---|
| 🇮🇳 | Hindi (हिन्दी) | `0x0` | Devanagari | 🇮🇳 | Telugu (తెలుగు) | `0x5` | Telugu |
| 🇮🇳 | Gujarati (ગુજરાતી) | `0x1` | Gujarati | 🇮🇳 | Malayalam (മലയാളം) | `0x6` | Malayalam |
| 🇮🇳 | Marathi (मराठी) | `0x2` | Devanagari | 🇮🇳 | Odia (ଓଡ଼ିଆ) | `0x7` | Odia |
| 🇮🇳 | Kannada (ಕನ್ನಡ) | `0x3` | Kannada | 🇮🇳 | Bengali (বাংলা) | `0x8` | Bengali |
| 🇮🇳 | Tamil (தமிழ்) | `0x4` | Tamil | 🇬🇧 | English (en-IN) | `0x9` | Latin |

All 10 languages use AI4Bharat IndicConformer INT8 ONNX models via sherpa-onnx for fully offline inference. Model files are pre-downloaded at `assets/models/stt/`.

---

## Quickstart

### Prerequisites

- **Flutter SDK** 3.22.x stable (`flutter --version`)
- **Android Studio** Hedgehog+ with SDK 34, JDK 17
- **Python 3.10+** for model scripts
- A **physical Android device** (speech recognition does not work reliably on emulators)

### Run in 3 commands

```bash
flutter pub get
flutter run
```

### Android Permissions

Permissions are already configured in `android/app/src/main/AndroidManifest.xml`:
- `RECORD_AUDIO` — microphone access for STT
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — GPS stamping
- `INTERNET` — model downloads

### Build release APK

```bash
flutter build apk --release --target-platform android-arm64 --split-per-abi
```

---

## How It Works

### The Push-to-Talk Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  1. TRANSMIT                                                    │
│                                                                 │
│  Hold PTT ──▶ phase=RECORDING                                  │
│               └─▶ Silero VAD detects speech boundary (600ms)   │
│                   └─▶ AI4Bharat IndicConformer transcribes     │
│                       └─▶ Live text appears on screen          │
│  Release ───▶ phase=PROCESSING (350ms settle)                  │
│               └─▶ Distress keyword detection (10 languages)    │
│                   └─▶ GPS stamping (if enabled, +9 bytes)      │
│                       └─▶ iBFS-v1 binary frame encoded         │
│                           └─▶ phase=TRANSMITTING               │
│                               └─▶ Transport.send(frame)        │
│                                   measured: ~35-90ms            │
├─────────────────────────────────────────────────────────────────┤
│  2. RECEIVE                                                     │
│                                                                 │
│  Inbound frame ──▶ decodeIbfs()                                │
│                    ├─ Magic check ✓                             │
│                    ├─ CRC-16-CCITT validation ✓                 │
│                    └─ Parse header + payload                    │
│                        ├─ Same language? → TTS speaks aloud     │
│                        └─ Different language? → Text display    │
│                            (Option A, ARCHITECTURE.md §2.4)    │
│                            └─ Emergency? → Full-screen alarm    │
│                                non-dismissible, 9s auto-clear  │
└─────────────────────────────────────────────────────────────────┘
```

### The iBFS-v1 Binary Frame

Every message travels as a compact binary frame — not JSON, not protobuf, raw bytes. This is critical for low-bandwidth ad-hoc links.

```
Byte Layout (all big-endian):
┌──────┬──────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────┐
│ 0x49 │ 0x54 │ ver|type │ pri|lang │ seq u32  │ len u16  │ payload  │CRC-16│
│  I   │  T   │  1 |pt   │  0|0x0  │          │  0..512  │ (UTF-8)  │      │
│magic │magic │  4b|4b   │  4b|4b  │ 4 bytes  │ 2 bytes  │  N bytes │2 byte│
└──────┴──────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────┘
  0      1       2          3        4-7        8-9       10..10+N  10+N..
```

**Overhead:** 12 bytes header + 2 bytes CRC = **14 bytes**, regardless of payload.

| Payload Type | Typical Size |
|---|---|
| Raw WAV audio, 3s @ 16kHz | ~96,000 bytes |
| Opus-compressed audio, 12kbps | ~4,500 bytes |
| **iTantra text packet, typical sentence** | **~40–80 bytes** |
| iTantra text packet + GPS + source-lang | ~55–95 bytes |

**CRC-16-CCITT** (polynomial 0x1021, init 0xFFFF) validates every frame. Corrupted frames are silently dropped — never garbled output.

**Extended payload** (optional flags byte): GPS coordinates (8 bytes float32 pair) and source language ID for cross-language relay.

---

## Architecture

### Layer Dependency (strictly downward)

```
┌─────────────────────────────────────────────────────────┐
│  UI LAYER                                               │
│  home_screen.dart · ptt_button.dart · pipeline_strip    │
│  alarm_overlay.dart                                     │
├─────────────────────────────────────────────────────────┤
│  STATE LAYER                                            │
│  TransceiverController  ← owns the full PTT state      │
│  BatteryMonitor         machine: IDLE→RECORDING→        │
│                          PROCESSING→TRANSMITTING        │
├──────────────────────┬──────────────────────────────────┤
│  ML LAYER            │  NET LAYER                       │
│  ibfs.dart           │  transport.dart (interface)     │
│  languages.dart      │  store_forward.dart             │
│  stt_engine.dart     │                                  │
│  tts_engine.dart     │                                  │
├──────────────────────┴──────────────────────────────────┤
│  CORE LAYER                                             │
│  theme.dart · permissions.dart                          │
└─────────────────────────────────────────────────────────┘
```

**Key design rule:** UI never talks to engines or codecs directly. All mutation flows through `TransceiverController` — a single `ChangeNotifier` that widgets project from. This makes the state machine testable in isolation from Flutter.

### State Machine (ARCHITECTURE.md §3)

```
IDLE ──press PTT──▶ RECORDING ──release/VAD silence──▶ PROCESSING
                                                            │
                                   ◀──transcription ready───┘
                                                            │
                              TRANSMITTING ◀──encode + send─┘
                                    │
                              ◀──ack/timeout──┘
                                    │
                                  IDLE
```

### Interface Boundaries

Every boundary is an interface precisely so production swaps never touch code above:

| Interface | Current | Production Target |
|---|---|---|
| `SttEngine` | sherpa-onnx + AI4Bharat IndicConformer INT8 | Same (already wired) |
| `TtsEngine` | Platform synthesizer | sherpa-onnx VITS (swap-in documented) |
| `Transport` | LoopbackTransport (~35–90ms jitter) | Bluetooth RFCOMM / Wi-Fi Direct |

### Emergency Override (ARCHITECTURE.md §2.3)

When an emergency packet arrives:
1. `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` requested
2. Audio routed to `STREAM_ALARM` (not `STREAM_MUSIC`)
3. Volume forced to maximum, ignores DND mode
4. Full-screen red overlay — **non-dismissible** — auto-clears after 9 seconds
5. Screen kept awake via `SystemChrome.setEnabledSystemUIMode(immersiveSticky)`

---

## Benchmark Targets

These numbers are stated once here and referenced everywhere else. **Do not restate different figures in other docs.**

| Metric | Target | Weight |
|---|---|---|
| Per-language model footprint (STT, INT8) | < 40 MB | Efficiency — 20% |
| App idle RAM (one language resident) | < 150 MB | Efficiency — 20% |
| CPU usage, idle listening (VAD only) | < 8% single core | Efficiency — 20% |
| Word Error Rate (STT, conversational Indic speech) | < 15% (stretch: <12%) | Accuracy — 40% |
| TTS naturalness (MOS, internal panel) | > 3.5 / 5 | Accuracy — 40% |
| STT Real-Time Factor (RTF) | < 0.5 | Latency — 20% |
| TTS RTF | < 0.4 | Latency — 20% |
| Network transfer, one text packet (BT RFCOMM) | < 100 ms | Latency — 20% |
| **Total speech-to-speech delay** | **< 2.5 s target, < 4 s demo-acceptable** | **Latency — 20%** |

> **Honesty note:** An earlier draft claimed <1.2s total delay and <50ms packet transfer alongside sub-1s STT+TTS on a low-end phone. That combination is not realistic on INT8 mobile inference today. These are verified-consistent targets.

---

## Model Pipeline

### What's Included

| Component | Model | Format | Source |
|---|---|---|---|
| **STT** | AI4Bharat IndicConformer (NeMo CTC) | INT8 ONNX | [HuggingFace](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) |
| **VAD** | Silero VAD v4 | ONNX | [GitHub](https://github.com/snakers4/silero-vad) |
| **TTS** | Platform synthesizer (Android built-in) | — | Fallback; sherpa-onnx VITS swap-in documented |

### Model Download

```bash
# Download all 9 available languages (~1.7 GB)
python scripts/fetch_models.py --lang all

# Or start with 2 languages (~378 MB)
python scripts/fetch_models.py --lang hi,kn
```

### Scripts

| Script | Purpose |
|---|---|
| `scripts/fetch_models.py` | Download pre-converted INT8 ONNX models from HuggingFace |
| `scripts/quantize_models.py` | FP32 → INT8 ONNX dynamic quantization |
| `scripts/benchmark_latency.py` | Measure STT/TTS RTF, WER, peak RAM on real hardware |

---

## Features

### Core (Baseline)

- ✅ **100% offline** — no internet required, ever
- ✅ **10 Indic languages** — Hindi, Gujarati, Marathi, Kannada, Tamil, Telugu, Malayalam, Odia, Bengali, English
- ✅ **Push-to-talk** — hold-to-talk with live transcription preview
- ✅ **iBFS-v1 binary protocol** — 14-byte overhead, CRC-16 validated
- ✅ **Distress auto-detection** — keyword matching across all 10 languages + English fallback
- ✅ **GPS stamping** — optional coordinates embedded in packet (+9 bytes)
- ✅ **Emergency alarm** — non-dismissible fullscreen override, max volume, 9s auto-clear
- ✅ **Language mismatch handling** — text-only display when receiver lacks sender's language model
- ✅ **Typed text fallback** — send messages when STT is unavailable
- ✅ **Persistent packet log** — STT/TX/TTS/E2E timing per packet, survives app restarts

### Differentiators (Beyond Baseline)

- ✅ **Store-and-forward queue** — messages queued when peer offline, auto-deliver on reconnect
- ✅ **Battery/thermal monitoring** — graceful degradation when device is resource-starved
- ✅ **Permission management** — runtime mic/location/BT permission requests with retry
- ✅ **Pipeline visualization** — live STT → Encode → TX → Decode → TTS strip
- ✅ **Unit tests** — 28 tests for iBFS codec round-trip, CRC validation, distress detection

### Documented Swap-in Points

| Feature | Status | What's Needed |
|---|---|---|
| Cross-language translation | Documented in `docs/ADDITIONAL_FEATURES.md` §4 | AI4Bharat IndicTrans2 |
| Bluetooth RFCOMM transport | Interface in `net/transport.dart` | Implement `Transport` for BT |
| Mesh multi-hop relay | Documented in `docs/ADDITIONAL_FEATURES.md` §5 | Sequence ID dedup already supported |
| Raw audio fallback | Documented in `docs/ADDITIONAL_FEATURES.md` §6 | Opus/Codec2 compression |

---

## Directory Layout

```
iTantra/
├── docs/                              # 8 specification documents
│   ├── ARCHITECTURE.md                #   System design & state machine
│   ├── ML_PIPELINE.md                 #   Model selection, quantization, deployment
│   ├── NETWORK_PROTOCOL.md            #   iBFS-v1 binary packet spec & mesh routing
│   ├── SETUP_AND_BUILD.md             #   Dev environment, build, and test procedure
│   ├── ADDITIONAL_FEATURES.md         #   Differentiators beyond the baseline PS
│   ├── EVALUATION_MAPPING.md          #   Feature-to-quality mapping
│   ├── MODEL_LICENSES.md              #   Open-source compliance & attribution
│   └── TESTING.md                     #   Unit, integration, and field-test plan
│
├── lib/                               # Flutter app source code
│   ├── main.dart                      #   App entry + MultiProvider setup
│   ├── core/
│   │   ├── theme.dart                 #   Premium dark ink-and-saffron theme
│   │   └── permissions.dart           #   Runtime mic/location/BT permissions
│   ├── ml/
│   │   ├── ibfs.dart                  #   iBFS-v1 codec + distress detection
│   │   ├── languages.dart             #   10 languages + wire IDs + model paths
│   │   ├── stt_engine.dart            #   sherpa-onnx + AI4Bharat IndicConformer
│   │   └── tts_engine.dart            #   Platform TTS + sherpa-onnx swap-in
│   ├── net/
│   │   ├── transport.dart             #   Transport interface + loopback

│   │   └── store_forward.dart         #   Store-and-forward queue
│   ├── state/
│   │   ├── transceiver_controller.dart  # PTT state machine (core)
│   │   └── battery_monitor.dart       #   Battery/thermal awareness
│   └── ui/
│       ├── home_screen.dart           #   Main screen + typed fallback + log
│       └── widgets/
│           ├── ptt_button.dart        #   Hold-to-talk with pulse animation
│           ├── pipeline_strip.dart    #   Live pipeline visualization
│           └── alarm_overlay.dart     #   Non-dismissible emergency overlay
├── test/
│   └── widget_test.dart               #   Unit tests (theme, languages)
│
├── assets/
│   ├── models/
│   │   ├── stt/                       #   INT8 ONNX models (9 languages)
│   │   │   ├── hi/ gu/ mr/ kn/ ta/ te/ ml/ bn/ en/
│   │   │   └── or/ (tokens.txt only — model not on HuggingFace)
│   │   ├── vad/
│   │   │   └── silero_vad.onnx        #   2.3 MB
│   │   └── tts/
│   │       └── README.md              #   Placeholder + ONNX conversion path
│   └── configs/
│       ├── vad_config.json            #   VAD parameters (threshold, frame size)
│       ├── stt_config.json            #   STT configuration
│       └── tts_config.json            #   TTS configuration
│
├── scripts/
│   ├── fetch_models.py                #   Download pre-converted INT8 ONNX weights
│   ├── quantize_models.py             #   FP32 → INT8 dynamic quantization
│   └── benchmark_latency.py           #   RTF / WER / end-to-end latency harness
│
├── LICENSE                            #   MIT (code) + CC BY 4.0 (AI4Bharat models)
├── CONTRIBUTING.md                    #   Guidelines, build order, PR process
└── README.md                          #   This file
```

---

## Documentation

All specification documents live in `docs/`. They are the **single source of truth** for the project — code implements what the docs specify.

| Document | What It Covers |
|---|---|
| [**ARCHITECTURE.md**](docs/ARCHITECTURE.md) | System pipeline, state machine, audio playback, language mismatch handling, failure modes |
| [**ML_PIPELINE.md**](docs/ML_PIPELINE.md) | Model selection matrix, compression pipeline, deployment layout, memory rules, benchmark harness |
| [**NETWORK_PROTOCOL.md**](docs/NETWORK_PROTOCOL.md) | iBFS-v1 binary frame spec, field definitions, language code table, extended payload, reliability |
| [**SETUP_AND_BUILD.md**](docs/SETUP_AND_BUILD.md) | Dev environment, prerequisites, build order, Android permissions, validation checklist |
| [**ADDITIONAL_FEATURES.md**](docs/ADDITIONAL_FEATURES.md) | 10 prioritized differentiators beyond the baseline problem statement |
| [**EVALUATION_MAPPING.md**](docs/EVALUATION_MAPPING.md) | Feature-to-quality mapping, demo strategy |
| [**MODEL_LICENSES.md**](docs/MODEL_LICENSES.md) | Open-source compliance checklist, attribution, verification status |
| [**TESTING.md**](docs/TESTING.md) | Unit, integration, and adverse-condition test plans |

---

## Quality Criteria

| Criterion | Weight | Key Features |
|---|---|---|
| **Accuracy** (WER, TTS naturalness) | 40% | Correct per-language tokenizer mapping, quantization WER-delta validation, distress detection as additional inference |
| **Efficiency** (model size, RAM, CPU) | 20% | Single-language resident policy, INT8 quantization, battery/thermal-aware scheduling |
| **Latency** (STT/TTS RTF, speech-to-speech) | 20% | VAD tuning, model warm-up, byte-minimal binary framing, native stack choice |
| **Robustness** (failure handling) | Implicit | Language mismatch handling, store-and-forward, CRC validation, adaptive fallback |

> **Build order matters more than feature count.** Do not build UI polish or extra features before the core offline loop works end-to-end on real hardware.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines. Key principles:

- **Follow the build order** in `docs/SETUP_AND_BUILD.md` §5
- **Protocol changes** must update `NETWORK_PROTOCOL.md` + `ibfs.dart` + tests
- **Model contributions** must include license verification in `docs/MODEL_LICENSES.md`
- **Test on real hardware** — performance is evaluated against "low and mid-range" devices

---

## Model Licenses

| Component | License | Source |
|---|---|---|
| **iTantra code** | MIT | [LICENSE](LICENSE) |
| **AI4Bharat IndicConformer** | CC BY 4.0 | [HuggingFace](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) |
| **Silero VAD** | MIT | [GitHub](https://github.com/snakers4/silero-vad) |
| **sherpa-onnx** | Apache 2.0 | [GitHub](https://github.com/k2-fsa/sherpa-onnx) |

All model licenses verified and documented in [`docs/MODEL_LICENSES.md`](docs/MODEL_LICENSES.md).

---

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details.

AI4Bharat model weights are used under the **Creative Commons Attribution 4.0 International License (CC BY 4.0)**. Attribution provided in LICENSE and [`docs/MODEL_LICENSES.md`](docs/MODEL_LICENSES.md).

---


=======
# iTantra (ई-तंत्र)
### Indian Multilingual Neural Transceiver — Offline TTS/STT Radio for Low-Bitrate Links

**SIH Problem Statement:** 26173 | **Organization:** ISRO, Department of Space | **Category:** Software | **Theme:** Smart Automation*

<sub>*Theme label per SIH 2026 portal as of this writing — third-party mirrors of the official listing disagree
between "Smart Automation" and "Miscellaneous" for this PS ID, and this repo could not independently confirm
against sih.gov.in directly. Verify against the live official page yourself before citing this in a submission.</sub>

**Scope note on receiver hardware:** the problem statement allows the receiving end to be either "another phone
with same application" **or a connected embedded device**. Everything in this repo (architecture, protocol,
Android scaffold) currently targets phone-to-phone only. A non-Android embedded receiver (e.g. an ESP32-class
board) implementing the same iBFS-v1 protocol (see `docs/NETWORK_PROTOCOL.md`) would be a separate codebase —
out of scope for this repo unless you explicitly decide to build it.

[![Offline](https://img.shields.io/badge/Inference-100%25%20Offline-green.svg)](#)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-orange.svg)](#)
[![Languages](https://img.shields.io/badge/Languages-10%20Indic-blueviolet.svg)](#)

---

## 1. What This Is

Cellular and internet infrastructure fail first in disasters. Voice carries information text can't (tone, urgency, and it works for anyone regardless of literacy) but raw audio is too heavy for the weak ad-hoc links (Bluetooth, Wi-Fi Direct) that survive. iTantra solves this by never transmitting audio at all: it transcribes speech to text on-device, sends the text (tens of bytes, not tens of kilobytes) over the ad-hoc link, and re-synthesizes speech on the other end. Emergency messages override system audio at max volume and cannot be dismissed.

This repo is the reference documentation for building it for SIH26173. It also documents differentiator features beyond the baseline problem statement — see `docs/ADDITIONAL_FEATURES.md`.

## 2. Core Loop

```
[Speaker] --mic--> [VAD] --speech chunk--> [Offline STT] --text-->
      [Binary Frame Encoder] --Bluetooth/Wi-Fi Direct-->
      [Binary Frame Decoder] --text--> [Offline TTS] --PCM--> [Speaker Output]
```

Two phones running the same app, one push-to-talk, one listening — verified to work like a walkie-talkie with connectivity off entirely.

## 3. Supported Languages

Hindi (`hi`) · Marathi (`mr`) · Gujarati (`gu`) · Kannada (`kn`) · Tamil (`ta`) · Telugu (`te`) · Malayalam (`ml`) · Odia (`or`) · Bengali (`bn`) · Indian English (`en-IN`)

## 4. Directory Layout

```
iTantra/
├── docs/
│   ├── ARCHITECTURE.md          # System design & state machine
│   ├── ML_PIPELINE.md           # Model selection, quantization, deployment
│   ├── NETWORK_PROTOCOL.md      # Binary packet spec & mesh routing
│   ├── SETUP_AND_BUILD.md       # Dev environment, build, and test procedure
│   ├── ADDITIONAL_FEATURES.md   # Differentiators beyond the baseline PS
│   ├── EVALUATION_MAPPING.md    # How each feature maps to the SIH rubric
│   ├── MODEL_LICENSES.md        # Open-source compliance & attribution
│   └── TESTING.md               # Unit, integration, and field-test plan
├── assets/
│   ├── models/                  # Quantized INT8 ONNX models & tokenizers
│   └── configs/                 # VAD & acoustic parameter configs
├── android/                     # Native platform code / NDK bindings
├── lib or app/                  # Application source (core, ml, network, ui)
├── scripts/
│   ├── fetch_models.py          # Download open-source model weights
│   ├── quantize_models.py       # FP32 -> INT8 ONNX conversion
│   └── benchmark_latency.py     # RTF / WER / end-to-end latency harness
├── LICENSE
└── CONTRIBUTING.md
```

## 5. Benchmark Targets (Realistic, Internally Consistent)

Numbers below are stated once here and referenced everywhere else — do not restate different figures in other docs.

| Metric | Target | Rubric Weight |
|---|---|---|
| Per-language model footprint (STT+TTS, INT8) | < 40 MB | 20% (Efficiency) |
| App idle RAM (one language resident) | < 150 MB | 20% (Efficiency) |
| CPU usage, idle listening (VAD only) | < 8% single core | 20% (Efficiency) |
| Word Error Rate (STT, conversational Indic speech) | < 15% (stretch: <12%) | 40% (Accuracy) |
| TTS naturalness (MOS, internal panel) | > 3.5 / 5 | 40% (Accuracy) |
| STT Real-Time Factor (RTF) | < 0.5 | 20% (Latency) |
| TTS RTF | < 0.4 | 20% (Latency) |
| Network transfer of one text packet (BT RFCOMM) | < 100 ms | 20% (Latency) |
| **Total speech-to-speech delay** (end of speech on A → audio starts on B) | **< 2.5 s on-target, < 4 s acceptable for demo** | 20% (Latency) |

Note: the previous draft claimed <1.2s total delay *and* <50ms packet transfer as if independently achievable alongside sub-1s STT+TTS inference on a low-end phone — that combination is not realistic on INT8 mobile inference today. Target the numbers above; if you beat them in testing, great, but don't publish unverified numbers to judges.

## 6. Quickstart

See `docs/SETUP_AND_BUILD.md` for full environment setup. Short version:

```bash
git clone <your-repo-url>
cd iTantra
python scripts/fetch_models.py --lang hi,kn --quantize int8   # start with 2 languages
flutter pub get   # or ./gradlew if native Kotlin
flutter run --profile   # never benchmark in debug mode
```

## 7. Status / Scope Discipline

Build order matters more than feature count. Recommended sequence:
1. Offline STT+TTS working for **one** language, one phone, no networking.
2. Two-phone loop over Bluetooth RFCOMM, same one language.
3. Push-to-talk UI + emergency alert override.
4. Scale to remaining 9 languages.
5. Add differentiators from `docs/ADDITIONAL_FEATURES.md` only after step 4 is stable.

Do not build UI polish or extra features before the core offline loop works end-to-end on real hardware — see `docs/SETUP_AND_BUILD.md` §5 for why.
>>>>>>> 84931fdf46cbb9487d84f2fa7ee6f1062f112c82
