# Cute Chat product source instructions

Read START_HERE.md and the owner's latest request. This public repository is a clean product snapshot, not the private development repository; do not import private history/artifacts/secrets. Existing publication is authorized for 0.4.16 Normal only. Future releases or production backend changes require the task owner's applicable authorization.

- Preserve the existing package, signing compatibility, single crypto/network owner, vault and transactional Signal state. Never roll ratchets back or reset user storage to fix a test.
- Use standard maintained cryptographic libraries; no novel cryptography or chat/file/media application-server relay. Contact invitations are not encryption keys.
- Respect Android permission, native credential, foreground service, capture and installer policies. Idle chat must not capture microphone. No hidden notification/service, fake Android call or guaranteed wake/force-stop bypass.
- Normal must exclude Accessibility/remote-control implementation; optional Assist is explicitly consent-bound. Never silently swap capability variants in the updater.
- Limited media must not be exported. Explicit Quick Share/Contacts exports are readable; document this accurately.
- Keep keys, tokens, service-account credentials, recovery/user material, build caches and local.properties out of Git. Do not publish debug APKs or private operational notes here.
- Record real tests separately from compilation/host tests; do not claim 100% security, universal connection, mathematically lossless video, Play approval or production certification.

Installer repair uses public PackageInstaller sessions with original strict byte/archive/signer checks, Android user consent and non-exported session/token/expiry-bound callbacks. Never bypass Play Protect, install-source settings, app authentication or background launch limits. Old updater must bootstrap from browser once; actual OEM rejection is unproven.

Nearby radio setup uses public SDK APIs with OS consent, receiver-only Advertise, real state/expiry/auth/no-call/no-transfer/destruction checks. Passwords stay in authenticated Bluetooth controls. Original user-confirmed installer repair is retained. Never bypass Play Protect, install-source settings, app authentication or background launch limits. Old updater must bootstrap from browser once; actual OEM rejection is unproven.
