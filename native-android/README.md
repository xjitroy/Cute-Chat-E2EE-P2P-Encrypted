# Native Android source — Cute Chat 0.4.16 / code 38

The current Kotlin source is in this directory. See the [product README](../README.md) for build commands, installation, limitations and validation; see [START_HERE](../START_HERE.md) for continuation. Main runtime code is `app/src/main/kotlin/app/cutiepie/cutie_pie/`; transactional owner/crypto/vault/transport code is under `core/`. Capability-specific implementations are in `src/normal/` and `src/assist/`.

Use JDK 21, Android SDK 36 and the Gradle wrapper. Build tasks must name Normal or Assist; published 0.4.16 APK is Normal, non-debuggable. Test the exact signed APK on real devices before production claims. Keep private credentials and local.properties outside Git. Never reset identity, Signal ratchets or encrypted history to fix an upgrade.
