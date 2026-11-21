#!/usr/bin/env python3
"""Inject the current scanner configuration into a detached historical worktree.

The same SpotBugs, FindSecBugs and PMD versions/rules must analyze both commits;
otherwise differences in scanner versions would appear as new findings. This
script changes only the temporary baseline worktree; never an actual branch.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re

RE = re.compile(r'(?s)(<profile>\s*<id>ci-quality</id>.*?</profile>)')

def inject(current: str, old: str) -> str:
    found = RE.search(current)
    if not found:
        raise ValueError('Candidate pom.xml has no ci-quality scanner profile')
    # A base commit starts containing ci-quality as soon as issue #53 is merged.
    # Rejecting that normal state would permanently break every subsequent Java PR.
    # Instead, replace its existing scanner profile with the candidate profile so
    # that the *same* scanner versions and rules analyze both commits.
    existing = list(RE.finditer(old))
    if len(existing) > 1:
        raise ValueError('Baseline POM contains duplicate ci-quality profiles')
    if existing:
        match = existing[0]
        updated = old[:match.start()] + found.group(1) + old[match.end():]
    elif '</profiles>' not in old:
        if old.count('</project>') != 1:
            raise ValueError('Baseline POM has no unique project root closing tag')
        updated = old.replace('</project>', '<profiles>\n'+found.group(1)+'\n</profiles>\n</project>', 1)
    else:
        updated = old.replace('</profiles>', found.group(1)+'\n</profiles>', 1)
    # The base may predate JaCoCo. Instrument its tests with the same version
    # as the candidate, in the temporary detached worktree only.
    plugin_pattern=re.compile(r'(?s)<plugin>\s*<groupId>org\.jacoco</groupId>.*?</plugin>')
    plugin=plugin_pattern.search(current)
    if plugin is None:raise ValueError('Candidate POM is missing JaCoCo instrumentation')
    # Match only the top-level Maven build. Old POMs put <resources> before
    # <plugins>; searching for '<build><plugins>' would match a nested profile.
    project, profiles, rest = updated.partition('<profiles>')
    if not plugin_pattern.search(project):
        build = re.search(r'(?s)<build>.*?<plugins>', project)
        if build:
            project = project[:build.end()] + '\n' + plugin.group(0) + project[build.end():]
        elif '</build>' in project:
            project = project.replace('</build>', '<plugins>' + plugin.group(0) + '</plugins></build>', 1)
        else:
            project += '<build><plugins>' + plugin.group(0) + '</plugins></build>\n'
        updated = project + profiles + rest
    if '<jacoco.minimum.line.coverage>' not in updated:
        prop=re.search(r'<properties>',updated)
        if prop:
            updated=updated[:prop.end()]+'\n<jacoco.minimum.line.coverage>0.05</jacoco.minimum.line.coverage>'+updated[prop.end():]
        else:
            root=re.search(r'<project(?:\s[^>]*)?>',updated)
            if root is None:raise ValueError('Baseline POM lacks root project')
            updated=updated[:root.end()]+'\n<properties><jacoco.minimum.line.coverage>0.05</jacoco.minimum.line.coverage></properties>'+updated[root.end():]
    return updated

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--candidate',type=Path,default=Path('pom.xml'))
    ap.add_argument('--baseline',type=Path,required=True)
    a=ap.parse_args()
    source=a.candidate.read_text(encoding='utf-8')
    destination=a.baseline
    if not destination.is_file():
        ap.error('Baseline pom does not exist')
    destination.write_text(inject(source,destination.read_text(encoding='utf-8')),encoding='utf-8')
    print('Injected identical CI scanner profile into the detached base revision')
if __name__ == '__main__': main()
