# RIC Player v0.1

Lightweight Android local + network media player.

## Features
- Local video/audio via Android document picker
- Direct HTTP/HTTPS URL playback
- HLS (.m3u8) and DASH (.mpd)
- MP4/MKV/WebM/MP3/AAC/FLAC/OGG/Opus and other formats supported by Media3 extractors + device codecs
- Picture-in-Picture
- Hardware decoding by default
- R8 + resource shrinking for small APK
- minSdk 26, targetSdk 37

## Size target
Release APK target: <= 20 MB. Exact size depends on toolchain and final resources. No bundled FFmpeg/LibVLC, because full native codec packs can exceed the 20 MB cap.

## Build
GitHub Actions automatically builds the release APK on push.

Local build requirements: JDK 17, Android SDK 37, Gradle 9.6.0, AGP 9.4.0.

    gradle :app:assembleRelease

APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
