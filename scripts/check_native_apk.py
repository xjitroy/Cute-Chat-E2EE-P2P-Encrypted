#!/usr/bin/env python3
"""Fail closed on unexpected permissions/package/signing for a native APK."""
from pathlib import Path
import os
import re
import subprocess
import sys
import zipfile

if len(sys.argv) != 2:
    raise SystemExit("usage: check_native_apk.py path/to.apk")

apk = Path(sys.argv[1]).resolve()
if not apk.is_file():
    raise SystemExit(f"APK not found: {apk}")

sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
versions = sorted((sdk / "build-tools").iterdir(), key=lambda p: tuple(int(x) if x.isdigit() else 0 for x in p.name.split(".")))
tools = versions[-1]
aapt = tools / "aapt2"
signer = tools / "apksigner"

permissions_dump = subprocess.check_output([str(aapt), "dump", "permissions", str(apk)], text=True)
permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", permissions_dump))
allowed = {
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.CHANGE_WIFI_STATE", "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE", "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN",
    "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO",
    "android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.CAMERA",
    "android.permission.DETECT_SCREEN_CAPTURE", "android.permission.READ_CONTACTS",
    "android.permission.VIBRATE", "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE", "android.permission.FOREGROUND_SERVICE_CAMERA",
    "android.permission.FOREGROUND_SERVICE_PHONE_CALL", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.MANAGE_OWN_CALLS", "android.permission.USE_FULL_SCREEN_INTENT",
    "android.permission.USE_BIOMETRIC", "com.google.android.c2dm.permission.RECEIVE",
    "app.cutiepie.cutie_pie.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
unexpected = permissions - allowed
if unexpected:
    raise SystemExit(f"Unexpected permissions: {sorted(unexpected)}")

bad = permissions & {
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_SMS", "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.SYSTEM_ALERT_WINDOW",
}
if bad:
    raise SystemExit(f"Forbidden broad permissions: {sorted(bad)}")

badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
version_match = re.search(r"package: name='app\.cutiepie\.cutie_pie' versionCode='38' versionName='0\.4\.17-(normal|assist)(-debug)?'", badging)
if not version_match:
    raise SystemExit("Unexpected package or version")

verified = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)], text=True)
expected = "36977d12a524211451eb0f228405622552923d6dd62dac3309e886e7f3ff4a55"
if f"Signer #1 certificate SHA-256 digest: {expected}" not in verified:
    raise SystemExit("APK signer does not match the previously published development certificate")

print(f"verified {apk.name}: package/version, stable signer and {len(permissions)} allowlisted permissions")

# Check the merged binary manifest, not only source declarations.
manifest = subprocess.check_output([str(aapt), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], text=True)
components = re.split(r"(?m)^ {10}E: (?:activity|service|receiver) ", manifest)
def component(name):
    entries = [entry for entry in components if f'android:name(0x01010003)="app.cutiepie.cutie_pie.{name}"' in entry]
    if len(entries) != 1:
        raise SystemExit(f"Missing/duplicate protected component: {name}")
    return entries[0]
if 'android:exported(0x01010010)=false' not in component("IncomingCallActivity"):
    raise SystemExit("Private call-only activity must not be exported")
is_assist = version_match.group(1) == "assist"
names = ["RemoteConsentActivity", "RemoteControlActivity", "RemoteControlService", "RemoteAppScope"]
if is_assist:
    for name in names[:2]:
        if 'android:exported(0x01010010)=false' not in component(name):
            raise SystemExit(f"Private control activity exported: {name}")
    remote = component("RemoteControlService")
    if 'android:permission(0x01010006)="android.permission.BIND_ACCESSIBILITY_SERVICE"' not in remote:
        raise SystemExit("Accessibility service lacks OS signature binding permission")
    print("Assist: scoped control activities and OS-protected Accessibility service verified")
else:
    if any(n in manifest for n in names) or "BIND_ACCESSIBILITY_SERVICE" in manifest or "android.accessibilityservice" in manifest:
        raise SystemExit("Normal must have no remote control/Accessibility component")
    with zipfile.ZipFile(apk) as z:
        if "res/xml/remote_assistance.xml" in z.namelist():
            raise SystemExit("Normal contains remote Accessibility resource")
        dex = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex",n))
        for n in names:
            if ("Lapp/cutiepie/cutie_pie/" + n + ";").encode() in dex:
                raise SystemExit("Normal contains remote Android implementation: " + n)
        if b"Landroid/accessibilityservice/AccessibilityService;" in dex:
            raise SystemExit("Normal contains AccessibilityService implementation/reference")
    print("Normal: no control service/activities/classes/Accessibility resource or binding")
