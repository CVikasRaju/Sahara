# 📻 iTantra: Offline Multilingual Neural Transceiver

![Android](https://img.shields.io/badge/Platform-Android_9.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Offline](https://img.shields.io/badge/Connectivity-100%25_Offline-FF4B4B?style=for-the-badge)
![AI](https://img.shields.io/badge/Edge_AI-ONNX_Runtime-005CED?style=for-the-badge)
![Mesh](https://img.shields.io/badge/Network-Wi--Fi_Direct_%7C_BLE-0078D4?style=for-the-badge)

**iTantra** is a decentralized, 100% offline Android communication hub designed for disaster relief and zero-connectivity zones. It transforms standard budget smartphones into a self-healing walkie-talkie mesh network powered entirely by Edge AI. 

By compressing transcribed speech into 38-byte micro-payloads, iTantra guarantees voice-to-voice communication across 10 Indian languages even when cellular towers, internet, and power grids completely collapse.

---

## 🚀 Core Innovations

*   **Ultra-Compressed Micro-Payloads (IBFS-v1):** Converts heavy voice audio into microscopic 38-byte binary text frames. This requires 99% less bandwidth than standard audio, ensuring delivery over severely degraded radio links.
*   **Sub-150MB Edge AI Pipeline:** Runs state-of-the-art STT and TTS models natively on-device. Uses an INT8-quantized, hot-swappable architecture to keep RAM footprint strictly under 150MB.
*   **Zero-Click NLP SOS Triage:** Utilizes Silero VAD to constantly monitor for spoken distress keywords (e.g., "Help", "Rescue"). Instantly broadcasts an emergency beacon with offline GPS coordinates without requiring screen interaction.
*   **Tactical OS Audio Override:** Incoming emergency SOS frames bypass Android's "Do Not Disturb" and silent profiles, seizing hardware audio focus to blast alerts at 100% volume.
*   **Delay-Tolerant Store & Forward:** Acts as an asynchronous data mule. If a user is completely isolated, the app caches SOS beacons and blasts them the exact microsecond another mesh node enters radio range.
*   **Multilingual Inclusivity:** Breaks the literacy and language barrier. A user speaks naturally in their regional language, the payload is transmitted, and the receiving device synthesizes the text back into natural, spoken audio.

---

## 🛠️ Technology Stack

*   **Application Core:** Flutter & Kotlin (Native Android Services)
*   **Edge Machine Learning:** ONNX Runtime C++ Bindings, Sherpa-ONNX
*   **Acoustic Models:** AI4Bharat IndicConformer (STT), VITS (TTS)
*   **Voice Activity Detection:** Silero VAD (TinyML)
*   **Networking:** Android Wi-Fi Direct (P2P), Bluetooth Low Energy (BLE GATT/Advertising)

---

## ⚙️ The Neural Transceiver Pipeline

1.  **Audio Capture:** Minimal-CPU Silero VAD detects natural speech pauses (0.45s) for hands-free operation.
2.  **Speech-to-Text (STT):** Local INT8 Indic models transcribe the audio to text.
3.  **Compression:** The text, sender ID, and offline GPS are crushed into a 38-byte binary packet.
4.  **Ad-Hoc Mesh Transport:** The payload hops silently across idle survivor smartphones acting as intermediate repeaters via Wi-Fi/BLE.
5.  **Text-to-Speech (TTS):** The receiving device decodes the packet and synthesizes it into natural spoken audio for the receiver.

---

## 📱 Hardware Compatibility
Designed strictly for inclusivity and disaster-resilience in developing regions:
*   **OS:** Android 9.0 (API 28) and above.
*   **Memory:** Runs smoothly on low-to-mid-range devices with 2GB+ RAM.
*   **Cloud Dependency:** `0%` (No APIs, no cloud servers, no cellular towers required).

---

## 👥 Contributors & Team
Developed by **Team Sahara** for the Smart India Hackathon. 
*An open-source initiative to build a resilient, voice-driven communication grid when all infrastructure collapses.*
