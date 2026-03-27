# Edge TTS Android

Android system TTS engine built with Java + Rust on top of `edge-tts-rust`.

## Features

- Android `TextToSpeechService` engine
- Material settings UI
- Microsoft Edge Read Aloud voices
- Rust JNI bridge with MP3-to-PCM decoding
- Multi-ABI split APK release workflow for GitHub Releases

## Build

Prerequisites:

- Android SDK + NDK `30.0.14904198`
- Rust toolchain
- `cargo-ndk`
- JDK 17 for Gradle

Build release APKs:

```bash
./gradlew assembleRelease
```

Optional release signing:

- Set `android.release.keystore.path`, `android.release.store.password`, `android.release.key.alias`, `android.release.key.password`
- Or provide the equivalent env vars:
  `ANDROID_RELEASE_KEYSTORE_PATH`,
  `ANDROID_RELEASE_STORE_PASSWORD`,
  `ANDROID_RELEASE_KEY_ALIAS`,
  `ANDROID_RELEASE_KEY_PASSWORD`

GitHub Actions signing secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

If signing values are absent, `assembleRelease` still produces unsigned release APKs.

Gradle builds the Rust JNI library for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

## Install and use

1. Download the APK matching your device ABI from GitHub Releases.
2. Install the APK.
3. Open the app and refresh the voice list.
4. Save a default voice.
5. Open Android system TTS settings and select `Edge TTS Engine`.

## Notes

- This is a network TTS engine. It does not provide offline voices.
- The service outputs PCM to Android's TTS framework after decoding upstream MP3 in Rust.
