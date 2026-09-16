# Additional Features — Differentiators Beyond the Baseline Problem Statement

The baseline (offline STT -> text transfer -> offline TTS, two-phone PTT loop) is the core of the project. These features are optional, individually toggleable additions that extend the same architecture toward real-world disaster-response needs. Build them in the priority order below, only after the baseline is stable (see SETUP_AND_BUILD.md §5).

## Priority 1 — Highest value per hour of build time

### 1. Distress-Intent Auto-Detection
Run a lightweight keyword/intent classifier on the STT text output (not the audio) to detect distress language ("help", "trapped", "injured", "fire", equivalents per language) and auto-set the packet's Priority flag to Emergency — instead of requiring the sender to manually mark it. Directly strengthens the accuracy story since it's additional on-device inference, and it's a genuine safety feature that's field-relevant.
- Implementation: a small classifier head on top of STT output text, or even a curated keyword-match list per language as a first pass if time is short.

### 2. GPS Location Stamping
Attach device GPS coordinates (works fully offline, no data connection needed) to transmitted messages using the extended payload format (see NETWORK_PROTOCOL.md §4). Turns "help needed" into "help needed, here" — the single highest-value addition for an actual disaster-response use case.

### 3. Store-and-Forward Queueing
If the target peer is out of range, queue the message locally (with its Sequence ID) and auto-deliver on reconnect, rather than failing silently. Uses Packet Type `0x4` (relay) from NETWORK_PROTOCOL.md. Cheap to build on top of the existing frame format, high practical value given disaster-zone connectivity is inherently intermittent.

## Priority 2 — Strong differentiator, more build effort

### 4. Cross-Language Relay (Translation)
Since text already sits mid-pipeline, add AI4Bharat IndicTrans2 (or similar) between STT and TTS so Person A speaking Gujarati can be heard by Person B in Tamil. Use the `HasSourceLang` extended payload flag (NETWORK_PROTOCOL.md §4) to signal the receiver which language to translate from. This turns the app from a "same-language walkie-talkie" into genuine cross-team coordination, which is exactly what multi-state disaster response actually needs.
- Caveat: adds a third model to your resident-memory budget per active conversation — test footprint impact carefully against the 20% efficiency metric before committing to this as core rather than optional.

### 5. Mesh / Multi-Hop Relay
Beyond direct two-phone pairing, allow a message to hop through intermediate phones running the app to reach someone outside direct radio range (Wi-Fi Direct group owner election, or BT bridging). Reflects real disaster-mesh precedent (goTenna, Bridgefy-style approaches). Use the Sequence ID for hop-dedup (a relay node drops packets it's already forwarded, per NETWORK_PROTOCOL.md §5).

## Priority 3 — Nice to have, lower marginal value

### 6. Adaptive Fallback to Compressed Raw Audio
If STT confidence is very low (heavy accent, dialect gap, high noise), fall back to sending a short, heavily compressed raw-audio clip (Opus or Codec2 at low bitrate) rather than silently failing or sending garbled text. Signals you understood your own architecture's failure modes rather than assuming STT always succeeds.

### 7. Transcription Confidence Display + Manual Correction
Show the sender the transcribed text with a confidence indicator before it transmits, allowing quick correction of misrecognized words — especially proper nouns and place names, which STT models handle worst. Cheap UI addition, meaningfully improves real-world reliability.

### 8. Group / Broadcast Mode
One-to-many PTT instead of strictly 1:1, closer to how real disaster-response radio channels work (a command post broadcasting to a full team rather than pairing individually).

### 8a. SOS Emergency Broadcast (implemented)
A red SOS button that fans an emergency alert (Packet Type `0x2`, see NETWORK_PROTOCOL.md §Packet Type) to every device in radio range — not one peer, all of them. Receiving devices treat it as "raise the alarm": a full-screen red overlay, a spoken message via TTS, and a native alert that is engineered to be un-missable:

- **App closed on the receiver** — a foreground standby service (`SosService.kt`) keeps the Flutter engine (and the BLE / Wi-Fi Direct mesh) alive after the app is swiped off the recents list, so the packet is still received. The alarm is raised by the native layer, which works with no Activity attached.
- **Silent mode / Do Not Disturb** — the alarm tone plays on `STREAM_ALARM` (what silent and DND modes do not silence), the SOS notification channel is created with `setBypassDnd(true)`, and with Notification Policy Access granted the receiver's DND is lifted entirely for the alert and restored after.
- **Screen off / locked** — the full-screen notification intent wakes the display and shows over the lock screen.
- **Delivery guarantee** — when no device is in range, the SOS is queued by the store-and-forward layer and re-sent automatically on reconnect.

The sender gets a 3-second confirm countdown (auto-cancel) so a pocket trigger cannot fire an irreversible village-wide alert, plus an optional note that is shown and spoken on every receiver. A readiness strip (Standby / DND / Battery chips) shows exactly what could block reception on this device and deep-links to the fix.

### 9. Battery/Thermal-Aware Model Scheduling
Throttle or unload models based on battery level and thermal state, not just RAM. Field phones will be resource-starved in ways a lab-tested phone isn't — this is a footprint-metric point worth documenting even in a minimal implementation.

### 10. Lightweight Payload Encryption
AES on the small text payload — negligible performance cost given payload sizes are tens of bytes, but signals security-mindedness appropriate for a government-facing distress system.

## What NOT to over-invest in
Given the quality weights (Accuracy 40%, Latency 20%, Efficiency 20%), a flashy feature list does not substitute for hitting your core STT WER and end-to-end latency targets. If you're choosing between polishing feature #6-10 versus tightening your baseline numbers, tighten the baseline — see EVALUATION_MAPPING.md for how the criteria actually weigh these choices.
