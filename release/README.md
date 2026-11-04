# iTantra — Android release build

| | |
|---|---|
| File | `app-arm64-v8a-release.apk` |
| App | iTantra (package `com.example.voice`) |
| Version | 0.2.0 (versionCode 2001) |
| ABI | `arm64-v8a` only (64-bit ARM phones) |
| Min Android | 7.0 (API 24) |
| Size | 63,723,048 bytes |

## Install

```bash
adb install -r app-arm64-v8a-release.apk
```

Or copy the APK to the phone and open it (allow "install from unknown sources").

The APK ships the VAD model only; speech-recognition models are downloaded on
first use, so the first run needs an internet connection.

## Verify the download

```bash
cd release
sha256sum -c SHA256SUMS.txt
```

Expected SHA-256:

```
cfe4ac9f0eadc844eaaed4a319eb7784e42b7c9b8d3a137a2f1b25b7d27eda8f
```

Note: this APK is signed with the Android debug certificate, which is fine for
sideloading and demos but is not Play Store–ready. A Play release needs a
private keystore (`android/key.properties` + `signingConfig`).
