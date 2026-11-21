#!/usr/bin/env python3
"""Offline regression tests for CVE-2026-107891 release VEX generation."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("vex_release", ROOT / "scripts/vex_release.py")
vex = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = vex
SPEC.loader.exec_module(vex)
DIGEST = "sha256:" + "a" * 64


class VexReleaseTests(unittest.TestCase):
    def test_generates_tightly_scoped_alpine_backport_statement(self):
        doc = vex.build_document(
            "ghcr.io/kosztyk/clamav-web-client", DIGEST, "2.1.0", "2026-11-20T07:00:00Z"
        )
        vex.validate_document(
            doc,
            image="ghcr.io/kosztyk/clamav-web-client",
            digest=DIGEST,
            version="2.1.0",
        )
        statement = doc["statements"][0]
        self.assertEqual(statement["status"], "not_affected")
        self.assertEqual(statement["justification"], "inline_mitigations_already_exist")
        self.assertEqual(
            statement["products"][0]["subcomponents"],
            [
                {
                    "@id": (
                        "pkg:apk/alpine/musl@1.2.6-r3"
                        "?arch=x86_64&os_name=alpine&os_version=3.24"
                    )
                },
                {
                    "@id": (
                        "pkg:apk/alpine/musl@1.2.6-r3"
                        "?arch=aarch64&os_name=alpine&os_version=3.24"
                    )
                },
            ],
        )
        self.assertEqual(statement["products"][0]["@id"], "pkg:docker/kosztyk/clamav-web-client@" + DIGEST + "?repository_url=ghcr.io")


    def test_package_purls_match_scout_1_29_alpine_identity(self):
        doc = vex.build_document(
            "docker.io/kosztyk/clamav-web-client", DIGEST, "2.1.0", "2026-11-20T07:00:00Z"
        )
        components = doc["statements"][0]["products"][0]["subcomponents"]
        self.assertIn(
            {
                "@id": (
                    "pkg:apk/alpine/musl@1.2.6-r3"
                    "?arch=x86_64&os_name=alpine&os_version=3.24"
                )
            },
            components,
        )
        self.assertIn(
            {
                "@id": (
                    "pkg:apk/alpine/musl@1.2.6-r3"
                    "?arch=aarch64&os_name=alpine&os_version=3.24"
                )
            },
            components,
        )

    def test_docker_hub_target_is_supported_but_unrelated_images_are_rejected(self):
        doc = vex.build_document(
            "docker.io/kosztyk/clamav-web-client", DIGEST, "2.1.0", "2026-11-20T07:00:00Z"
        )
        vex.validate_document(
            doc,
            image="docker.io/kosztyk/clamav-web-client",
            digest=DIGEST,
            version="2.1.0",
        )
        with self.assertRaises(vex.VexError):
            vex.build_document("docker.io/example/other", DIGEST, "2.1.0")

    def test_tampering_status_package_or_digest_fails_closed(self):
        doc = vex.build_document(
            "ghcr.io/kosztyk/clamav-web-client", DIGEST, "2.1.0", "2026-11-20T07:00:00Z"
        )
        for mutation in ("status", "package", "digest"):
            candidate = json.loads(json.dumps(doc))
            if mutation == "status":
                candidate["statements"][0]["status"] = "affected"
            elif mutation == "package":
                candidate["statements"][0]["products"][0]["subcomponents"][0]["@id"] = "pkg:apk/alpine/musl@1.2.6-r2?arch=x86_64&os_name=alpine&os_version=3.24"
            else:
                candidate["statements"][0]["products"][0]["@id"] = candidate["statements"][0]["products"][0]["@id"].replace("a" * 64, "b" * 64)
            with self.subTest(mutation=mutation), self.assertRaises(vex.VexError):
                vex.validate_document(
                    candidate,
                    image="ghcr.io/kosztyk/clamav-web-client",
                    digest=DIGEST,
                    version="2.1.0",
                )

    def test_cli_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "CVE-2026-107891.vex.json"
            doc = vex.build_document(
                "ghcr.io/kosztyk/clamav-web-client", DIGEST, "2.1.0", "2026-11-20T07:00:00Z"
            )
            out.write_text(json.dumps(doc))
            vex.load_and_validate(
                out,
                image="ghcr.io/kosztyk/clamav-web-client",
                digest=DIGEST,
                version="2.1.0",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
