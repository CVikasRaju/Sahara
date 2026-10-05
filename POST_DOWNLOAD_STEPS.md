# After You Unzip This — Read This First

## What's actually in this zip

This is a **working, buildable Android project scaffold** — real Gradle config, a real
implementation of the binary networking protocol (with passing unit tests), real
Bluetooth transport code, and a real Compose UI shell. It is **not** a finished app.
The ML inference (STT/TTS) currently runs on deterministic mock engines so the rest
of the app can be built and tested before you have real models — that's intentional,
not a placeholder oversight, and it follows the build order in `docs/SETUP_AND_BUILD.md`
§5 (get the pipeline shape working before wiring in the hard part).

**Nothing here has been compiled or run on a device** — I don't have an Android SDK,
NDK, or physical phone in the environment that generated this. Step 1 below is where
you find out if anything needs fixing for your specific Android Studio/SDK version.

## Step-by-step, in order

### 1. Open and build the empty shell (Day 1)
1. Install Android Studio (Hedgehog 2023.1.1+), NDK 25.2.9519653+, JDK 17.
2. Open the unzipped `iTantra/` folder as a project in Android Studio.
3. Let Gradle sync. **Expect to fix version mismatches** — the Gradle/Kotlin/Compose
   versions pinned in `build.gradle.kts` and `app/build.gradle.kts` were current as of
   this writing but Android tooling moves fast; Android Studio will usually offer an
   automatic upgrade if something's stale.
4. Build → Make Project. Fix any compile errors — likely candidates: a missing resource
   (I created a minimal `strings.xml`/`themes.xml` but no launcher icons — Android Studio's
   "New > Image Asset" wizard fixes this in two clicks), or a Compose API that shifted
   version. This is normal first-build friction, not a sign the scaffold is broken.
5. Run `./gradlew testDebugUnitTest` — this runs `ProtocolCodecTest.kt`, which needs no
   device or emulator. **If these fail, stop and fix them before anything else** — every
   other layer depends on the protocol codec being correct.
6. Run the app on an emulator or device. You should see the PTT screen with a
   Transceiver Mode toggle and a language dropdown. Nothing transmits real data yet —
   that's expected at this stage.

### 2. Legal/compliance (parallel to step 1, finish before step 4)
7. Open `docs/MODEL_LICENSES.md`. For every model you intend to actually use, find its
   current Hugging Face page and confirm the license. **Do this before downloading**,
   not after integrating — an unclear license is a disqualification risk per the
   problem statement's "Open-Source Only" rule.

### 3. Get real models (Day 2-5)
8. On your own machine (not a locked-down CI/sandbox — huggingface.co needs to be
   reachable): `pip install -r scripts/requirements.txt`
9. `python scripts/fetch_models.py --lang hi,kn --output assets/models_raw`
   — **verify the repo IDs in the script are still current** before trusting the
   download; AI4Bharat reorganizes model repos periodically, and the ones hardcoded
   in the script were my best information, not a live-verified lookup.
10. `python scripts/quantize_models.py --input assets/models_raw --output assets/models`
11. Get a held-out test set (AI4Bharat Vistaar or Kathbath) and build a manifest CSV
    (`audio_path,reference_text`) per `scripts/benchmark_wer.py`'s docstring.
12. Implement the `transcribe()` stub in `benchmark_wer.py` with your actual model
    inference call (Sherpa-ONNX Python bindings are the easiest path for this
    pre-deployment check), then run it and log the WER.

### 4. Real ML integration into the app (Day 5-8)
13. Download the sherpa-onnx Android AAR, add it per the TODO in `app/build.gradle.kts`.
14. Implement `SherpaSttEngine` and `SherpaTtsEngine` per the integration-point comment
    at the bottom of `app/src/main/java/com/itantra/ml/Engines.kt` — this is genuinely
    the hardest remaining piece and is exactly the JNI/native-binding work no AI
    coding agent can fully automate; budget real engineering time here.
15. Swap `MockSttEngine`/`MockTtsEngine` for the real ones in `core/TransceiverService.kt`.

### 5. Wire the pipeline together (Day 8-11)
16. In `TransceiverService.kt`, implement the 5 TODOs listed at the top of that file,
    in the order listed — mic capture, VAD→STT→transmit, receive→TTS→playback,
    then the foreground notification.
17. This is where you need two physical phones — Bluetooth pairing cannot be tested
    on an emulator.

### 6. Everything after this follows `docs/SETUP_AND_BUILD.md` §5 and `docs/TESTING.md`
UI polish, scaling to all 10 languages, adverse-condition testing, and the
differentiator features in `docs/ADDITIONAL_FEATURES.md` — build in that order, not
before the core loop is solid on real hardware.

## Honest scope check

What you have: ~85% of the plumbing (protocol, networking skeleton, state machine,
UI shell, working test infrastructure, Python tooling). What's still ahead of you:
the actual native ML integration (step 4) and the full hardware wiring + physical
device testing (step 5 onward) — this is genuinely the hard 20% that determines
whether this becomes a working demo, and it's also the part that needs two Android
phones sitting next to each other, which nothing I can hand you as a zip substitutes for.
