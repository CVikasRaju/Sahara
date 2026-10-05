# Contributing

## Before you touch code
1. Read `POST_DOWNLOAD_STEPS.md` first — it's the actual entry point.
2. Read `docs/ARCHITECTURE.md` and `docs/NETWORK_PROTOCOL.md` before modifying
   networking or state-machine code — both have design decisions with reasons
   attached; don't change the protocol framing without updating both the spec
   doc and `ProtocolCodecTest.kt` in the same change.

## Workflow
- Run `./gradlew testDebugUnitTest` before every commit that touches
  `network/` or `core/` — these are the fast, no-device tests.
- Any change to `assets/models/**` must have a corresponding row filled in
  `docs/MODEL_LICENSES.md` before merging.
- Log real device-measured numbers (not estimates) when updating any
  latency/WER/RAM claim in the docs — see `docs/TESTING.md` §5.

## Code style
- Kotlin: standard official style (already set in `gradle.properties`).
- Python: keep scripts dependency-light and runnable standalone — no shared
  internal framework, since these are meant to be run ad hoc on a dev machine.
