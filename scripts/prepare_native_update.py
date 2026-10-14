#!/usr/bin/env python3
"""Prepare a reviewed public Normal release payload LOCALLY. Never publish or access credentials."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--apk", type=Path, required=True)
p.add_argument("--tag", required=True)
p.add_argument("--out", type=Path, required=True)
args = p.parse_args()
assert re.fullmatch(r"[A-Za-z0-9._-]{1,80}", args.tag), "Unsafe release tag"
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
bt = sdk / "build-tools/36.0.0"
apk = args.apk.resolve()
badging = subprocess.check_output([str(bt / "aapt2"), "dump", "badging", str(apk)], text=True)
match = re.search(r"package: name='app\.cutiepie\.cutie_pie' versionCode='(\d+)' versionName='(\d+\.\d+\.\d+)-normal'", badging)
assert match and "application-debuggable" not in badging, "Only a non-debuggable Normal APK can be prepared"
cert = subprocess.check_output([str(bt / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
assert "36977d12a524211451eb0f228405622552923d6dd62dac3309e886e7f3ff4a55" in cert, "Existing update signer must be retained"
code, version = int(match[1]), match[2]
sdk_match = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
assert sdk_match, "Missing minSDK in Android build-tools metadata"
minimum = int(sdk_match[1])
name = f"Cute-Chat-{version}-normal.apk"
assert 0 < apk.stat().st_size <= 128 * 1024 * 1024
args.out.mkdir(parents=True, exist_ok=True)
shutil.copy2(apk, args.out / name)
manifest = {"schema": 1, "package_name": "app.cutiepie.cutie_pie", "version_code": code,
            "version_name": version, "tag": args.tag, "min_sdk": minimum,
            "apks": [{"variant": "normal", "name": name, "bytes": apk.stat().st_size,
                      "sha256": hashlib.file_digest(apk.open("rb"), "sha256").hexdigest()}]}
(args.out / "cute-chat-update.json").write_text(json.dumps(manifest, indent=2) + "\n")
(args.out / "PENDING-PUBLIC-APPROVAL.md").write_text(f"""# Not published — owner approval required

Target public repository: https://github.com/xjitroy/Cute-Chat-E2EE-P2P-Encrypted
Target non-draft/non-prerelease tag: `{args.tag}`. Code {code}, Normal {version}.
Public release assets MUST include `{name}` and `cute-chat-update.json` under this exact tag.
This program only prepares files locally. It never publishes, changes visibility, uploads history or includes a private signing key/GitHub token.

Before asking for final public publication approval: provide reviewed clean-source/security documentation, exact APK/manifest hashes, signing disclosures and test evidence. Only a clean product snapshot may go to the supplied public repository; keep debug/Assist development artifacts and full development history in the original private repository.

Current APK is non-debuggable but signed by the original development certificate; this is NOT proof of production signing, Play approval or physical device testing. Existing installed signing certificate must match; do not reset chat storage or silently switch signers. Public publication is not performed by this script.

Each app entry checks published compatible versions, prompts only above the installed version code, and lets the user dismiss. Download is explicit; Android permission and installer confirmation remain required. Same version never prompts. No APK/manifest is fetched from the private repository and no credentials are embedded.
""")
print(json.dumps({"prepared_only": True, "public_publish": "NOT_PERFORMED", "manifest": manifest}, indent=2))
