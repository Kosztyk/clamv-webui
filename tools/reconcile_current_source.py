#!/usr/bin/env python3
"""Create a FULL corrected-source ZIP from the existing 2.3.9+ checkout.

Does not modify the input checkout. Preserves existing native ClamdTop and REST API files.
Rejects incomplete REST integration instead of generating nonfunctional placeholders.

Example:
 python3 tools/reconcile_current_source.py --source /path/to/clamav --output /tmp/clamav-webui-merged-full.zip
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

SECURITY = Path("src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java")
SHELL = Path("src/main/resources/templates/fragments/shell.html")
JS = Path("src/main/resources/static/js/rest-api.js")
CSS = Path("src/main/resources/static/css/rest-api.css")
REST_C = Path("src/main/java/info/trizub/clamav/webclient/web/RestMonitorController.java")
REST_T = Path("src/test/java/info/trizub/clamav/webclient/web/RestMonitorIntegrationTest.java")
SMOKE = Path("src/test/java/info/trizub/clamav/webclient/web/UiSmokeTest.java")
EXCLUDE_DIRS = {".git", "target", "node_modules", "data", ".idea", ".vscode", "__pycache__", ".mvn/wrapper", ".gradle"}
EXCLUDE_FILES = {".env", "dot.env", "bootstrap-admin-password", ".DS_Store"}


def copy_filter(directory, names):
    return [name for name in names if name in EXCLUDE_DIRS or name in EXCLUDE_FILES or name.startswith(".phase0-buildfix-backup-")]


def reconcile(src: Path):
    security = src / SECURITY
    shell = src / SHELL
    smoke = src / SMOKE
    if not security.exists() or not shell.exists():
        raise ValueError("Incomplete Spring source: SecurityConfig.java and/or fragments/shell.html missing")
    has_rest = (src / REST_C).is_file() or (src / REST_T).is_file()
    if has_rest and not (src / JS).is_file():
        raise ValueError("Newer REST monitor source exists, but real rest-api.js is missing. Refusing a stub.")

    s = security.read_text(encoding="utf-8")
    roles = '.requestMatchers("/api-reports", "/api-reports/**").hasAnyRole("OPERATOR","ADMIN")'
    if not re.search(r'\.requestMatchers\(\s*"/api-reports"', s):
        marker = '                .requestMatchers("/api/**").hasAnyRole("OPERATOR","ADMIN")'
        if s.count(marker) != 1:
            raise ValueError("Unexpected SecurityConfig rule layout; cannot safely position /api-reports permissions")
        s = s.replace(marker, '                ' + roles + "\n" + marker, 1)
    if has_rest:
        summary = '.requestMatchers(HttpMethod.GET, "/rest-monitor/summary").hasAnyRole("VIEWER","OPERATOR","ADMIN")'
        detail = '.requestMatchers("/rest-monitor/**").hasAnyRole("OPERATOR","ADMIN")'
        if summary not in s or detail not in s:
            if "/rest-monitor/" in s:
                raise ValueError("Existing custom /rest-monitor security rules require manual review")
            marker = '                .requestMatchers("/api/**").hasAnyRole("OPERATOR","ADMIN")'
            s = s.replace(marker, '                ' + summary + "\n                " + detail + "\n" + marker, 1)
    security.write_text(s, encoding="utf-8")

    h = shell.read_text(encoding="utf-8")
    if has_rest and '@{/js/rest-api.js}' not in h:
        marker = '<script defer th:src="@{/js/tabs-pjax.js}"></script>'
        if h.count(marker) != 1:
            raise ValueError("Cannot find the normal Thymeleaf shell JS insertion point")
        h = h.replace(marker, '<script defer th:src="@{/js/rest-api.js}"></script>\n' + marker, 1)
    if has_rest and (src / CSS).is_file() and '@{/css/rest-api.css}' not in h:
        marker = '<link rel="stylesheet" th:href="@{/css/phase0.css}"/>'
        if h.count(marker) != 1:
            raise ValueError("Cannot find the normal Thymeleaf shell CSS insertion point")
        h = h.replace(marker, marker+'\n<link rel="stylesheet" th:href="@{/css/rest-api.css}"/>', 1)
    shell.write_text(h, encoding="utf-8")

    if smoke.is_file():
        t=smoke.read_text(encoding="utf-8")
        before='mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection())'
        after='mvc.perform(get("/dashboard").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())'
        if before in t:
            if 'import org.springframework.http.MediaType;' not in t:
                raise ValueError("UiSmokeTest missing MediaType import")
            t=t.replace(before,after)
        anchor='mvc.perform(get("/api/jobs")).andExpect(status().isForbidden());'
        new='mvc.perform(get("/api-reports")).andExpect(status().isForbidden());'
        if anchor in t and new not in t: t=t.replace(anchor,anchor+'\n        '+new)
        smoke.write_text(t, encoding="utf-8")
    # Core assertions for exactly the issues reported on 2026-10-28.
    assert '.requestMatchers("/api-reports"' in s
    if has_rest:
        assert '@{/js/rest-api.js}' in h
        assert (src / JS).exists()
    return has_rest


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',required=True,type=Path,help='Current full checkout with newest REST API modules')
    parser.add_argument('--output',required=True,type=Path,help='Destination full source ZIP')
    args=parser.parse_args()
    root=args.source.resolve()
    if not (root/'pom.xml').is_file(): raise SystemExit('ERROR: --source must point at the directory containing pom.xml')
    out=args.output.resolve()
    if root in out.parents or out==root: raise SystemExit('ERROR: --output must be outside source directory')
    if out.exists():raise SystemExit('ERROR: output archive already exists: '+str(out))
    with tempfile.TemporaryDirectory(prefix='clamav-reconcile-') as tmp:
        staging=Path(tmp)/'clamav-webui-phase0-merged'
        shutil.copytree(root,staging,ignore=copy_filter,symlinks=False)
        has_rest=reconcile(staging)
        out.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=7) as z:
            for p in sorted(staging.rglob('*')):
                if p.is_file():z.write(p,arcname=p.relative_to(staging.parent))
        with zipfile.ZipFile(out) as z:
            if z.testzip():raise RuntimeError('ZIP integrity verification failed')
            names=z.namelist()
            if has_rest and not any(n.endswith('/js/rest-api.js') for n in names):raise RuntimeError('REST API JavaScript omitted')
        print('Full source archive:',out)
        print('REST integration:', 'preserved and reconciled' if has_rest else 'not present in the supplied source')
        print('No tests were skipped. Run docker compose build --no-cache clamav-web-client from an extracted archive.')
        print('Keep this ZIP private if your checkout includes credentials in configuration files.')

if __name__=='__main__': main()
