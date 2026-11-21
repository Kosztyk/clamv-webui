#!/usr/bin/env python3
"""Fail closed if the release regresses below the ClamAV 1.4.7 security patch."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.4.7"
APK = "1.4.7-r0"
SOURCE_SHA256 = "3f93bf28b193677d09427a19602bf07af30629edbce93fd7f92b92a64e75c027"
SCANNER_DIGEST = "sha256:b7357e2c1c59480628839df301b0d27923561389b36cf341911e1dd9e02cdd05"
SCANNER_IMAGE = f"clamav/clamav:{VERSION}@{SCANNER_DIGEST}"

checks = 0
def check(condition, message):
    global checks
    if not condition:
        raise AssertionError(message)
    checks += 1
    print("PASS:", message)

apkbuild = (ROOT / "docker/clamav/APKBUILD").read_text()
check("pkgver=1.4.7" in apkbuild and "pkgrel=0" in apkbuild,
      "native ClamAV package is pinned to 1.4.7-r0")
check("Cisco-Talos/clamav/releases/download/clamav-$pkgver/clamav-$pkgver.tar.gz" in apkbuild,
      "native ClamAV package uses the immutable-version upstream release asset")
check(SOURCE_SHA256 in apkbuild and "clamav-1.4.7.tar.gz" in apkbuild,
      "native ClamAV source is pinned to the publisher release digest")

dockerfile = (ROOT / "Dockerfile").read_text()
for token in [
    "clamav-libs-1.4.7-r0.apk",
    "clamav-1.4.7-r0.apk",
    "'clamav-libs>=1.4.7-r0'",
]:
    check(token in dockerfile, f"Dockerfile enforces {token}")

smoke = (ROOT / "scripts/issue37-smoke.sh").read_text()
check('"clamav-libs>=1.4.7-r0"' in smoke,
      "Docker runtime smoke enforces the installed ClamAV library 1.4.7 security floor")
check('! apk info -e clamav' in dockerfile and '! apk info -e clamav' in smoke,
      "final runtime explicitly rejects the ClamAV meta-package while retaining the 1.4.7 libraries")

pin = json.loads((ROOT / "docs/security/clamd-image-pin.json").read_text())
check(pin["image"] == SCANNER_IMAGE and pin["tag"] == VERSION and pin["digest"] == SCANNER_DIGEST,
      "default ClamD service pin is the verified official 1.4.7 index")
check(pin.get("platforms") == [{"architecture": "amd64", "os": "linux"}],
      "scanner pin documents only the runtime platform verified on Docker Hub")

for rel in [
    "Dockerfile.clamd", ".env.example", "docker-compose.yml",
    "examples/compose/shared-paths.override.yaml", "examples/compose/standalone-h2.yaml",
]:
    text = (ROOT / rel).read_text()
    check(SCANNER_IMAGE in text, f"{rel} uses the verified ClamAV 1.4.7 scanner pin")

# Historical validation records may truthfully mention 1.4.6. Build/runtime sources may not.
for rel in [
    "Dockerfile", "Dockerfile.clamd", ".env.example", "docker-compose.yml",
    "docker/clamav/APKBUILD", "scripts/issue37-smoke.sh",
    "scripts/test-clamd-compose-isolation.py",
    "examples/compose/shared-paths.override.yaml", "examples/compose/standalone-h2.yaml",
]:
    check("1.4.6" not in (ROOT / rel).read_text(), f"{rel} has no stale ClamAV 1.4.6 runtime pin")

print(f"ClamAV security-floor checks passed ({checks} checks)")
