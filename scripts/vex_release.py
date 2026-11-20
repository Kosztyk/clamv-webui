#!/usr/bin/env python3
"""Generate and validate the release-scoped OpenVEX statement for CVE-2026-107891.

The Alpine 3.24 branch backports the musl fix in 1.2.6-r3. Docker Scout may
compare the package against Alpine edge's 1.2.6-r6 revision and report a false
positive. This module emits a narrowly scoped OpenVEX statement bound to the
exact image digest and musl package/version shipped by this release.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

CONTEXT = "https://openvex.dev/ns/v0.2.0"
PREDICATE_TYPE = CONTEXT
CVE = "CVE-2026-107891"
PACKAGE = "musl"
PACKAGE_VERSION = "1.2.6-r3"
ALPINE_BRANCH = "3.24"
STATUS = "not_affected"
JUSTIFICATION = "inline_mitigations_already_exist"
AUTHOR = "https://github.com/Kosztyk/clamv-webui"
ADVISORY = "https://security.alpinelinux.org/vuln/CVE-2026-107891"
ALLOWED_IMAGES = {
    "ghcr.io/kosztyk/clamav-web-client",
    "docker.io/kosztyk/clamav-web-client",
}
DIGEST_RE = re.compile(r"sha256:[0-9a-f]{64}\Z")
VERSION_RE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?\Z")


class VexError(ValueError):
    pass


def _require(ok: bool, message: str) -> None:
    if not ok:
        raise VexError(message)


def _timestamp(value: str | None) -> str:
    if value:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise VexError("timestamp must be ISO-8601") from exc
        _require(parsed.tzinfo is not None, "timestamp must include a timezone")
        return parsed.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _image_product(image: str, version: str, digest: str) -> str:
    # Docker Scout 1.26+ requires the image digest as the pkg:docker version.
    # For non-Docker-Hub registries, keep the registry in repository_url rather
    # than in the PURL package path so Scout can match the SBOM product.
    _ = version  # Version is validated separately and retained in release evidence.
    registry, repository = image.split('/', 1)
    base = f"pkg:docker/{repository}@{digest}"
    return base if registry == "docker.io" else f"{base}?repository_url={registry}"


def _package_products() -> list[dict[str, str]]:
    # Docker Scout 1.29+ retains Alpine architecture/OS qualifiers in binary
    # package PURLs. The release supports linux/amd64 and linux/arm64, whose
    # Alpine architecture identifiers are x86_64 and aarch64 respectively.
    # Keep both exact PURLs in the release-scoped VEX so either platform scan
    # matches the installed musl package without broadening beyond Alpine 3.24.
    return [
        {
            "@id": (
                f"pkg:apk/alpine/{PACKAGE}@{PACKAGE_VERSION}"
                f"?arch=x86_64&os_name=alpine&os_version={ALPINE_BRANCH}"
            )
        },
        {
            "@id": (
                f"pkg:apk/alpine/{PACKAGE}@{PACKAGE_VERSION}"
                f"?arch=aarch64&os_name=alpine&os_version={ALPINE_BRANCH}"
            )
        },
    ]


def build_document(image: str, digest: str, version: str, timestamp: str | None = None) -> dict:
    _require(image in ALLOWED_IMAGES, f"unsupported image repository: {image}")
    _require(DIGEST_RE.fullmatch(digest) is not None, "invalid sha256 image digest")
    _require(VERSION_RE.fullmatch(version) is not None, "invalid release version")
    ts = _timestamp(timestamp)
    token = hashlib.sha256(f"{image}\n{digest}\n{version}\n{CVE}\n{PACKAGE_VERSION}".encode()).hexdigest()
    return {
        "@context": CONTEXT,
        "@id": f"https://github.com/Kosztyk/clamv-webui/security/vex/{CVE}/{token}",
        "author": AUTHOR,
        "timestamp": ts,
        "version": 1,
        "statements": [
            {
                "vulnerability": {"name": CVE},
                "products": [
                    {
                        "@id": _image_product(image, version, digest),
                        "subcomponents": _package_products(),
                    }
                ],
                "status": STATUS,
                "justification": JUSTIFICATION,
                "impact_statement": (
                    "Alpine Linux 3.24 backports the CVE-2026-107891 musl fix in "
                    "musl 1.2.6-r3. The 1.2.6-r6 revision reported by Docker Scout "
                    "belongs to a different Alpine package branch and is not the fixed "
                    "version required for Alpine 3.24. Alpine security status: " + ADVISORY
                ),
                "action_statement": (
                    "Retain Alpine 3.24 musl 1.2.6-r3 and reassess this VEX statement "
                    "for every new image digest or Alpine branch change."
                ),
                "timestamp": ts,
            }
        ],
    }


def validate_document(doc: dict, *, image: str | None = None, digest: str | None = None, version: str | None = None) -> None:
    _require(isinstance(doc, dict), "VEX document must be an object")
    _require(doc.get("@context") == CONTEXT, "unexpected OpenVEX context")
    _require(doc.get("author") == AUTHOR, "unexpected VEX author")
    _require(doc.get("version") == 1, "unexpected VEX document version")
    _require(isinstance(doc.get("@id"), str) and doc["@id"].startswith(AUTHOR + "/security/vex/"), "invalid VEX document id")
    _timestamp(doc.get("timestamp"))
    statements = doc.get("statements")
    _require(isinstance(statements, list) and len(statements) == 1, "exactly one VEX statement is required")
    statement = statements[0]
    _require(statement.get("vulnerability", {}).get("name") == CVE, "wrong vulnerability")
    _require(statement.get("status") == STATUS, "CVE must be marked not_affected")
    _require(statement.get("justification") == JUSTIFICATION, "wrong VEX justification")
    _require("Alpine Linux 3.24" in statement.get("impact_statement", ""), "impact statement must explain Alpine 3.24 backport")
    _require(ADVISORY in statement.get("impact_statement", ""), "Alpine advisory evidence URL missing")
    products = statement.get("products")
    _require(isinstance(products, list) and len(products) == 1, "exactly one image product is required")
    product = products[0]
    components = product.get("subcomponents")
    _require(isinstance(components, list) and components == _package_products(), "VEX must scope to Alpine 3.24 musl 1.2.6-r3 for x86_64 and aarch64")
    if any(value is not None for value in (image, digest, version)):
        _require(all(value is not None for value in (image, digest, version)), "image, digest and version must be validated together")
        _require(image in ALLOWED_IMAGES, f"unsupported image repository: {image}")
        _require(DIGEST_RE.fullmatch(digest or "") is not None, "invalid expected digest")
        _require(VERSION_RE.fullmatch(version or "") is not None, "invalid expected version")
        _require(product.get("@id") == _image_product(image or "", version or "", digest or ""), "VEX product does not match release image digest")


def load_and_validate(path: Path, *, image: str | None = None, digest: str | None = None, version: str | None = None) -> dict:
    try:
        doc = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise VexError(f"cannot read VEX document: {exc}") from exc
    validate_document(doc, image=image, digest=digest, version=version)
    return doc


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    generate = sub.add_parser("generate")
    generate.add_argument("--image", required=True)
    generate.add_argument("--digest", required=True)
    generate.add_argument("--version", required=True)
    generate.add_argument("--timestamp")
    generate.add_argument("--output", type=Path, required=True)

    validate = sub.add_parser("validate")
    validate.add_argument("--file", type=Path, required=True)
    validate.add_argument("--image")
    validate.add_argument("--digest")
    validate.add_argument("--version")

    args = parser.parse_args()
    try:
        if args.command == "generate":
            doc = build_document(args.image, args.digest, args.version, args.timestamp)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(doc, indent=2, sort_keys=False) + "\n")
            validate_document(doc, image=args.image, digest=args.digest, version=args.version)
            print(f"PASS: generated release-scoped OpenVEX for {CVE}: {args.output}")
        else:
            provided = (args.image, args.digest, args.version)
            if any(value is not None for value in provided) and not all(value is not None for value in provided):
                raise VexError("--image, --digest and --version must be supplied together")
            load_and_validate(args.file, image=args.image, digest=args.digest, version=args.version)
            print(f"PASS: validated release-scoped OpenVEX for {CVE}: {args.file}")
        return 0
    except VexError as exc:
        print(f"VEX VALIDATION FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
