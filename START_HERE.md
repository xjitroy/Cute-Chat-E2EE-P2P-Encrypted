# Continue Cute Chat 0.4.15

Read README.md, SECURITY.md, PRIVACY.md, AGENTS.md, SOURCE-RECORD.json and VALIDATION.json first. This is a clean public Kotlin product snapshot; version code 36, package app.cutiepie.cutie_pie, Normal public APK. The existing signed binaries use the original development certificate. No signing keys or private development history are included.

The compiled native sources match the recorded source snapshot; only the source README was replaced for public use. Production runtime source is unchanged. Entry points: MainActivity.kt (UI), core/NativeOwner.kt (single crypto/network owner), NativeMediaCalls.kt (call media), NativeRtcNetworks.kt (local-only SDK network monitor), core/NativeWifiHandoff.kt (approved hotspot handoff), core/NativeLargeFiles.kt / core/FileTransferTuning.kt (bounded streaming), core/TabSwipeRules.kt (navigation), core/AppUpdateRules.kt (public updater validation). Use the Gradle wrapper with JDK21/SDK36 and flavor-specific tasks from README.md.

208 JVM tests per flavor pass; lint zero errors; 22 Android cases per flavor compile but did not execute. Prioritize real two-phone QR rotation/decoding, permission return/denial, gesture/reply handling, native authentication, audio/video calls, direct sharing and in-place signed update tests. Keep those outcomes separate from compilation and host tests.

Do not restore stale ratchets, reset vaults, weaken authentication, silently change signing keys or add cloud payload relays. Phone Contacts and Quick Share Downloads are intentionally readable exports. Google encrypted backup remains deferred. Original production coordination was not redeployed for this UI/publication update.
