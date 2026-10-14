# Cute Chat

Native Kotlin Android chat, audio/video calls and original-byte file sharing, with end-to-end encrypted direct peer transports and encrypted app-private storage.

[Download Normal APK](https://github.com/xjitroy/Cute-Chat-E2EE-P2P-Encrypted/releases/download/cute-chat-0.4.14/Cute-Chat-0.4.14-normal.apk) · [Release notes](https://github.com/xjitroy/Cute-Chat-E2EE-P2P-Encrypted/releases/tag/cute-chat-0.4.14) · [Privacy](PRIVACY.md) · [Security](SECURITY.md)

## Install 0.4.14

Download the **Normal APK** from the release above. Android may ask you to allow installation from your browser; installation still requires Android's confirmation. Android 7.0/API 24 or later is required. Both peers should use the latest compatible version. Install over the existing Cute Chat app to retain encrypted history; **do not uninstall or clear app data**. Normal excludes Accessibility-based remote control. Debug binaries and development history are not published here.

This non-debuggable APK retains the original **development signing certificate** for update compatibility. It is a public development candidate, not a Play Store release, independent security certification or a guarantee that all device/network combinations work. Respect any Android/Play Protect warning; do not disable device protection.

বাংলা: উপরের Download থেকে Normal APK নিন। আগের অ্যাপের ওপর ইনস্টল করুন; uninstall বা data clear করবেন না। Android-এর ইনস্টল করার সম্মতি প্রয়োজন।

## What changed

- Camera scanner and your signed QR share one page, with an enlarge option, masked private ID and profile shortcut.
- Corrected camera-preview rotation and display-change handling. Live OEM-camera verification remains pending.
- Four bottom tabs: Chats, Calls, Media and Sharing. Scan & ID remains available through QR/contact actions and the swipe route.
- Native drag/flick/cancel tab animations, RTL and reduced-motion handling; text input, sliders, replies and call controls remain separate.
- Consistent E2EE/direct-P2P entry and pull-down explanations in English, Bengali, Hindi and Russian.
- QR identity-loading refresh and enlarged-QR bitmap cleanup fixes.

## Privacy and connection model

Existing Signal cryptography protects chat content; large transfers use standard authenticated secretstream, and WebRTC calls use direct encrypted media transport. Cloudflare/FCM/Google sign-in process connection, notification and authentication **metadata**, distinct from chat/file/media payloads. This does not mean the app never contacts a server. Direct/STUN-only connections may fail on restrictive NAT/firewalls; no TURN payload relay is configured. Real-time video compression is lossy, not mathematically lossless.

Private history/media are encrypted in the app vault. Accepted Quick Share files are intentionally saved as **readable copies** in Downloads/Cute Chat, outside that vault. Phone Contacts exports are readable and may sync through the phone's selected account. Google encrypted contact backup is not implemented. Limited-view media is not intended for export; universal screenshot prevention and zero device traces cannot be guaranteed. See [PRIVACY.md](PRIVACY.md).

## Updates

The updater introduced in 0.4.13 checks this repository's published releases. It offers only a **strictly higher compatible versionCode**, never the version already installed. Download is explicit and verified against package/capability/size/hash/current signer; Android handles installation consent. This release contains Normal only, so Assist installations are not silently converted. Versions before 0.4.13 need a manual download. GitHub sideload updates are separate from Google Play's update mechanism.

## Build and continue development

Open `native-android/` with JDK 21 and Android SDK 36. Create an ignored `native-android/local.properties` pointing to your SDK. Use the included Gradle wrapper. Do not commit signing keys, tokens, service-account JSON, SDK paths or user data.

```sh
cd native-android
./gradlew :app:testNormalDebugUnitTest :app:lintNormalDebug :app:assembleNormalDebug
./gradlew :app:assembleNormalRelease
```

A local release build must be signed using your own secure build configuration. Updating existing installed apps requires their original signer; no private signing key is distributed. The optional Assist source flavor is retained for review/development, but no Assist APK is in this public release. Read [START_HERE.md](START_HERE.md), [AGENTS.md](AGENTS.md) and [the 0.4.14 implementation note](docs/NATIVE_0.4.14_QR_NAVIGATION.md).

## Validation

Both flavors passed **194 JVM tests** with no failures; lint reported zero errors (Normal 110 / Assist 122 warnings). **22 Android instrumentation cases per flavor compiled, zero executed** for this candidate because no adb target was attached. Physical QR rotations, decoding, gestures, permissions, authentication, calls, background behavior and in-place updates still need real-device tests. See [VALIDATION.json](VALIDATION.json). Public downloads are separately hash-verified during publication.

This is a clean product snapshot, with no private Git history, signing/service-account keys, user screenshots or build caches. APK SHA-256: `63f97628e6cceb6be8fed5c153df7ae265424166d059e674635614cee30b57e9`.
