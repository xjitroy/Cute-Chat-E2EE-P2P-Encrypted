# Cute Chat

Native Kotlin Android chat, audio/video calls and original-byte file sharing, with end-to-end encrypted direct peer transports and encrypted app-private storage.

[Download Normal APK](https://github.com/xjitroy/Cute-Chat-E2EE-P2P-Encrypted/releases/download/cute-chat-0.4.16/Cute-Chat-0.4.16-normal.apk) · [Release notes](https://github.com/xjitroy/Cute-Chat-E2EE-P2P-Encrypted/releases/tag/cute-chat-0.4.16) · [Privacy](PRIVACY.md) · [Security](SECURITY.md)

## Install 0.4.16

Download the **Normal APK** from the release above. Android may ask you to allow installation from your browser; installation still requires Android's confirmation. Android 7.0/API 24 or later is required. Both peers should use the latest compatible version. Install over the existing Cute Chat app to retain encrypted history; **do not uninstall or clear app data**. Normal excludes Accessibility-based remote control. Debug binaries and development history are not published here.

This non-debuggable APK retains the original **development signing certificate** for update compatibility. It is a public development candidate, not a Play Store release, independent security certification or a guarantee that all device/network combinations work. Respect any Android/Play Protect warning; do not disable device protection.

বাংলা: উপরের Download থেকে Normal APK নিন। আগের অ্যাপের ওপর ইনস্টল করুন; uninstall বা data clear করবেন না। Android-এর ইনস্টল করার সম্মতি প্রয়োজন।

## In-app installation repair

0.4.16 stages the verified APK in an Android-owned PackageInstaller session instead of asking OEM installer Activities to read an app-private URI. Exact size/hash/current signer/archive checks and Android install-source/user-action consent remain mandatory. Callback receiver is private and session/token/expiry bound; background only offers a generic tap-to-confirm notification. No silent installation or protection bypass.

**If an older in-app updater reports a package parsing error, download this APK using Chrome/Downloads and install it over the existing app once. Do not uninstall or clear app data.** The newly installed version handles subsequent updates through the repaired route. The exact OEM rejection has not been captured and actual phone installation is not yet verified. If Chrome also rejects it, obtain the exact Android error/version before diagnosing policy/signer/storage/compatibility.

বাংলা: পুরোনো অ্যাপের ভেতর থেকে package error এলে এই APK একবার Chrome/Downloads থেকে আগের অ্যাপের ওপর ইনস্টল করুন। আনইনস্টল বা data clear করবেন না। নতুন সংস্করণে পরের আপডেট Android-এর নিজস্ব installation session দিয়ে হবে। ফোনে বাস্তব ইনস্টল পরীক্ষা এখনও বাকি।

## Automatic Send / Receive preparation

Send prepares Bluetooth and sender Wi-Fi as needed; Receive requests modern Advertise permission and Android discoverability. After a verified sender is accepted, the receiver creates a temporary local hotspot. Its password travels only in the authenticated Signal-encrypted Bluetooth handoff, then the sender joins via Android’s public Wi-Fi API. No password typing for this supported path. Existing same-Wi-Fi links are reused.

Android may require Bluetooth pairing/visibility or Wi-Fi connection consent; these prompts cannot be bypassed. The receiver does not enable station Wi-Fi and accidentally disable its hotspot. Older Android may need Location switched on for nearby setup; no GPS coordinates are read. Settings refusal/cancellation does not authorize setup. No active call/transfer network change, hidden toggles, process binding or guaranteed device compatibility. Temporary app-owned hotspot/network requests are released after idle/cancel, without forcibly disabling unrelated user radios.

বাংলা: Send ফাইল বাছার পরে প্রয়োজনীয় Bluetooth/Wi-Fi setup করবে। Receive-এ sender-কে Accept করার পরে অস্থায়ী hotspot হবে; encrypted Bluetooth দিয়ে password যাবে এবং sender-এর Wi-Fi যুক্ত হবে। Android pairing/connection prompt অনুমোদন করতে হবে; manual password লাগবে না supported auto path-এ।

## In-app installation repair

0.4.17 stages the verified APK in an Android-owned PackageInstaller session instead of asking OEM installer Activities to read an app-private URI. Exact size/hash/current signer/archive checks and Android install-source/user-action consent remain mandatory. Callback receiver is private and session/token/expiry bound; background only offers a generic tap-to-confirm notification. No silent installation or protection bypass.

**If an older in-app updater reports a package parsing error, download this APK using Chrome/Downloads and install it over the existing app once. Do not uninstall or clear app data.** The newly installed version handles subsequent updates through the repaired route. The exact OEM rejection has not been captured and actual phone installation is not yet verified. If Chrome also rejects it, obtain the exact Android error/version before diagnosing policy/signer/storage/compatibility.

বাংলা: পুরোনো অ্যাপের ভেতর থেকে package error এলে এই APK একবার Chrome/Downloads থেকে আগের অ্যাপের ওপর ইনস্টল করুন। আনইনস্টল বা data clear করবেন না। নতুন সংস্করণে পরের আপডেট Android-এর নিজস্ব installation session দিয়ে হবে। ফোনে বাস্তব ইনস্টল পরীক্ষা এখনও বাকি।

## What changed

- Local-only Wi-Fi/hotspot network changes are reported through the supported WebRTC monitor, including real Android handles and offline-Wi-Fi online state. The process's default network is not changed.
- Unified Plan remote video tracks are handled through onTrack and receiver refresh; continued ICE and interoperable LAN H264/VP8 ordering retain modern/repair alternatives.
- Bounded4MiB Wi-Fi live window, buffered TCP sockets, priority controls, coalesced checkpoints and removal of the old35ms-per-record live throttle address application throughput limits. Standard encryption, original bytes,32KiB wire records and authenticated final length/SHA256 remain.
-250ms verified-byte samples make rate/ETA resistant to small-chunk bursts. Progress widgets update in place rather than rebuilding the controls each second.
- Clear Send/Wi-Fi and Receive/Hotspot roles, four-stage native animation, automatic setup and manual fallback instructions. Existing shared Wi-Fi is reused.
- Negotiated5GHz capability tries the public Android36 configuration API where both radios support it, with compatible fallback. Android permissions and sender acceptance remain mandatory.

4GB in2–4minutes requires roughly17–34MB/s actual sustained end-to-end speed.5GHz alone cannot guarantee it: hardware, storage, CPU, thermal state and interference matter. Real two-phone hotspot/video/large-file timing tests remain pending. The16MiB real TCP/integrity/interleaved-chat regression is host transport evidence, not measured phone Wi-Fi speed.

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

A local release build must be signed using your own secure build configuration. Updating existing installed apps requires their original signer; no private signing key is distributed. The optional Assist source flavor is retained for review/development, but no Assist APK is in this public release. Read [START_HERE.md](START_HERE.md), [AGENTS.md](AGENTS.md) and [the 0.4.16 implementation note](docs/NATIVE_0.4.16_LOCAL_VIDEO_STREAMING.md).

## Validation

Both flavors passed **208 JVM tests** with no failures; lint reported zero errors (Normal 125 / Assist 137 warnings). **24 Android instrumentation cases per flavor compiled, zero executed** for this candidate because no adb target was attached. Physical QR rotations, decoding, gestures, permissions, authentication, calls, background behavior and in-place updates still need real-device tests. See [VALIDATION.json](VALIDATION.json). Public downloads are separately hash-verified during publication.

This is a clean product snapshot, with no private Git history, signing/service-account keys, user screenshots or build caches. APK SHA-256: `cac78235fac549e98c8ad3066a78145aa8e9346e4161759b49c197e43b0d8fc1`.
