#!/usr/bin/env python3
"""Issue #50 regression: only editing the Maven version promotes a release."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('version_check', ROOT/'scripts/check-versions.py')
checks = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(checks)


def run() -> None:
    version = checks.pom_version(ROOT)
    assert checks.inspect(ROOT) == [], 'Real source version inventory is inconsistent: ' + repr(checks.inspect(ROOT))
    assert checks.inspect(ROOT, tag='v'+version) == []
    bad_version = '0.0.1' if version != '0.0.1' else '9.9.9'
    mismatch = checks.inspect(ROOT, tag='v' + bad_version)
    assert any('Tag/POM mismatch' in e for e in mismatch), mismatch
    assert any('Tag/POM mismatch' in e for e in checks.inspect(ROOT, tag='V'+version)), 'uppercase tags rejected'
    print('PASS: canonical beta tag works, mismatched stable/uppercase tag fails')
    with tempfile.TemporaryDirectory(prefix='issue50-') as temp:
        dst=Path(temp)
        paths=['pom.xml','docker-compose.yml','docker-compose.configuration-center.yaml','docker-compose.environment.yaml','docker-compose.minimal.yaml','README.md','cli/cmd/clamctl/main.go','cli/tools/build.go',
               'cli/internal/cli/client.go','cli/README.md','scripts/release-gate.sh','scripts/clamav-release-gate.sh',
               '.github/workflows/ci.yml','.github/workflows/release.yml',
               'src/main/resources/application.properties', 'src/main/resources/openapi/clamav-v1.yaml',
               'src/main/resources/configuration-center/setup.html',
               'src/main/resources/templates/login.html','src/main/resources/templates/error.html',
               'src/main/resources/templates/fragments/shell.html',
               'src/main/java/info/trizub/clamav/webclient/web/UiModelAdvice.java']
        for name in paths:
            out=dst/name
            out.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(ROOT/name,out)
        assert checks.inspect(dst) == []
        pom=dst/'pom.xml'
        old=pom.read_text();new_version='9.8.7' if version != '9.8.7' else '9.8.8'
        pom.write_text(old.replace('<version>'+version+'</version>','<version>'+new_version+'</version>',1))
        assert checks.pom_version(dst)==new_version
        assert checks.inspect(dst,tag='v'+new_version)==[], 'A POM-only bump must require no source edits'
        assert any('Tag/POM mismatch' in e for e in checks.inspect(dst,tag='v'+version))
        print('PASS: POM-only version bump validates across app, OpenAPI, CLI, Compose and README')
        prop=dst/'src/main/resources/application.properties'
        prop.write_text(prop.read_text().replace('clamav.app.version='+checks.TOKEN,'clamav.app.version=2.0.0'))
        assert any('application.properties' in e for e in checks.inspect(dst))
        prop.write_text((ROOT/'src/main/resources/application.properties').read_text())
        api=dst/'src/main/resources/openapi/clamav-v1.yaml'
        api.write_text(api.read_text().replace('  version: "'+checks.TOKEN+'"','  version: "1.2.0-mcp"',1))
        assert any('openapi/' in e for e in checks.inspect(dst))
        api.write_text((ROOT/'src/main/resources/openapi/clamav-v1.yaml').read_text())
        compose=dst/'docker-compose.yml'
        compose.write_text(compose.read_text().replace('${CLAMAV_WEBUI_IMAGE_REF:-clamav-web-client:${CLAMAV_WEBUI_IMAGE_TAG:-local}}','clamav-web-client:2.0.0-local'))
        assert any('Compose' in e or 'docker-compose.yml' in e for e in checks.inspect(dst))
        compose.write_text((ROOT/'docker-compose.yml').read_text())
        cli=dst/'cli/cmd/clamctl/main.go';cli.write_text(cli.read_text().replace('"development"', '"2.0.0"'))
        assert any('cli/cmd' in e for e in checks.inspect(dst))
        cli.write_text((ROOT/'cli/cmd/clamctl/main.go').read_text())
        readme=dst/'README.md';readme.write_text(readme.read_text().replace('**Application version:** set only in [`pom.xml`]', '**Application version:** 2.0.0'))
        assert any('README.md' in e for e in checks.inspect(dst))
        readme.write_text((ROOT/'README.md').read_text())
        release=dst/'scripts/release-gate.sh';release.write_text(release.read_text().replace('python3 scripts/check-versions.py','echo SKIP'))
        assert any('release-gate.sh' in e for e in checks.inspect(dst))
        release.write_text((ROOT/'scripts/release-gate.sh').read_text())
        print('PASS: application, OpenAPI, Compose, CLI, README and release gate drift all fail closed')
        out=dst/'target/classes'
        for path in checks.FILTERED_FILES:
            file=out/path
            file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text((dst/'src/main/resources'/path).read_text().replace(checks.TOKEN,new_version))
        assert checks.inspect(dst, tag='v'+new_version, built=True)==[]
        bad=out/'application.properties';bad.write_text(bad.read_text().replace('clamav.app.version='+new_version,'clamav.app.version=2.0.0'))
        assert any('target/classes/application.properties' in e for e in checks.inspect(dst,built=True))
        print('PASS: simulated Maven filtered build output detects stale metadata')
    print('PASS: issue #50 version consistency regression scenarios')

if __name__ == '__main__':
    run()
