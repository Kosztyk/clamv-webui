#!/usr/bin/env python3
"""Fail-closed release provenance checks (Git tag, POM, main ancestry, green CI gate).

Uses the GitHub REST API with a read-only GITHUB_TOKEN on release workflows.
The exact tagged commit, not a branch or an unrelated successful PR check, is verified.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

TAG_PATTERN = re.compile(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:[0-9A-Za-z-]+)(?:\.[0-9A-Za-z-]+)*)?\Z')
REPOSITORY_PATTERN = re.compile(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z')
SHA_PATTERN = re.compile(r'[0-9a-f]{40}\Z')


class ReleaseError(Exception):
    pass


def git(*args: str) -> str:
    completed = subprocess.run(['git', *args], capture_output=True, text=True, check=False)
    if completed.returncode:
        raise ReleaseError('Git cannot resolve the expected release ref: ' + completed.stderr.strip())
    return completed.stdout.strip()


def project_version(pom: Path) -> str:
    root = ET.parse(pom).getroot()
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    version = root.findtext('m:version', namespaces=namespace)
    if not version:
        raise ReleaseError('POM project version is missing; inherited parent version is not a release version')
    return version


class GitHubApi:
    def __init__(self, token: str, repo: str):
        if not token:
            raise ReleaseError('GITHUB_TOKEN is required for checking the main CI gate')
        self.token, self.repo = token, repo

    def get(self, resource: str, params: dict[str, str | int] | None = None) -> dict:
        if params:
            resource += '?' + urlencode(params)
        url = 'https://api.github.com/repos/' + self.repo + '/' + resource
        request = Request(url, headers={
            'Accept': 'application/vnd.github+json',
            'Authorization': 'Bearer ' + self.token,
            'X-GitHub-Api-Version': '2022-11-28',
            'User-Agent': 'clamav-webui-release-verification',
        })
        try:
            with urlopen(request, timeout=25) as response:
                data = json.load(response)
        except (HTTPError, URLError, ValueError, TimeoutError) as ex:
            raise ReleaseError('Unable to verify release provenance with GitHub API') from ex
        if not isinstance(data, dict):
            raise ReleaseError('Unexpected GitHub API response')
        return data


def remote_tag_commit(api: GitHubApi, tag: str) -> str:
    # Recheck at draft creation/publication: fail if a tag was moved after validation.
    ref = api.get('git/ref/tags/' + quote(tag, safe=''))
    obj = ref.get('object') or {}
    for _ in range(3):
        sha = obj.get('sha', '')
        if not isinstance(sha, str) or not SHA_PATTERN.fullmatch(sha):
            raise ReleaseError('Remote tag has no valid commit/object hash')
        if obj.get('type') == 'commit':
            return sha
        if obj.get('type') != 'tag':
            raise ReleaseError('Remote tag does not point to a commit')
        obj = (api.get('git/tags/' + sha).get('object') or {})
    raise ReleaseError('Tag contains too many nested annotated tags')


def validate(tag: str, checked_out: str, pom_version: str, api: GitHubApi, *, check_gate: bool = True) -> str:
    if not TAG_PATTERN.fullmatch(tag):
        raise ReleaseError('Tag must be canonical SemVer (example: v2.1.0 or v2.1.0-beta2)')
    if pom_version != tag[1:]:
        raise ReleaseError('Tag/POM mismatch: ' + tag + ' != v' + pom_version)
    if not SHA_PATTERN.fullmatch(checked_out):
        raise ReleaseError('Checked-out commit is not a full SHA')
    remote_commit = remote_tag_commit(api, tag)
    if remote_commit != checked_out:
        raise ReleaseError('Tag does not point to the checked-out commit (possible moved tag)')
    if not check_gate:
        return checked_out

    # SHA...main is ahead or identical only when the exact tag commit is on main.
    comparison = api.get('compare/' + checked_out + '...main')
    if comparison.get('status') not in ('ahead', 'identical'):
        raise ReleaseError('Tagged commit is not reachable from main')

    runs_resource = 'actions/workflows/ci.yml/runs'
    runs: list[dict] = []
    for page in range(1, 4):
        response = api.get(runs_resource, {'head_sha': checked_out, 'event': 'push',
                                           'branch': 'main', 'per_page': 100, 'page': page})
        batch = response.get('workflow_runs')
        if not isinstance(batch, list):
            raise ReleaseError('CI workflow runs response missing workflow_runs')
        runs.extend(r for r in batch if r.get('head_sha') == checked_out and
                    r.get('head_branch') == 'main' and r.get('event') == 'push')
        if len(batch) < 100:
            break
    if not runs:
        raise ReleaseError('No main-push CI workflow run found for the tagged commit')
    # Never select an older successful attempt if a newer run failed or is still running.
    runs.sort(key=lambda r: (r.get('created_at') or '', r.get('run_number') or 0, r.get('run_attempt') or 0), reverse=True)
    latest = runs[0]
    if latest.get('status') != 'completed' or latest.get('conclusion') != 'success':
        raise ReleaseError('Latest main CI workflow run for this commit is not green')
    run_id = latest.get('id')
    if not isinstance(run_id, int):
        raise ReleaseError('CI run is missing a valid ID')
    jobs: list[dict] = []
    for page in range(1, 5):
        result = api.get(f'actions/runs/{run_id}/jobs', {'filter': 'latest', 'per_page': 100, 'page': page})
        batch = result.get('jobs')
        if not isinstance(batch, list):
            raise ReleaseError('CI run jobs response missing jobs')
        jobs.extend(batch)
        if len(batch) < 100:
            break
    gates = [job for job in jobs if job.get('name') == 'gate' and job.get('run_id', run_id) == run_id]
    if len(gates) != 1 or gates[0].get('status') != 'completed' or gates[0].get('conclusion') != 'success':
        raise ReleaseError('Required CI gate is missing, cancelled, skipped or not green')
    return checked_out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--expected-sha', default='')
    parser.add_argument('--remote-ref-only', action='store_true')
    parser.add_argument('--pom', type=Path, default=Path('pom.xml'))
    options = parser.parse_args()
    try:
        repo = os.environ.get('GITHUB_REPOSITORY', '')
        if not REPOSITORY_PATTERN.fullmatch(repo):
            raise ReleaseError('GITHUB_REPOSITORY is not an owner/repo identifier')
        api = GitHubApi(os.environ.get('GITHUB_TOKEN', ''), repo)
        if options.remote_ref_only:
            if not SHA_PATTERN.fullmatch(options.expected_sha):
                raise ReleaseError('--expected-sha is required for remote ref verification')
            commit = remote_tag_commit(api, options.tag)
            if commit != options.expected_sha:
                raise ReleaseError('Remote release tag was moved since initial verification')
        else:
            # Using HEAD and the remote tag commit also supports annotated tags.
            checked_out = git('rev-parse', 'HEAD')
            commit = validate(options.tag, checked_out, project_version(options.pom), api)
        print('PASS: release tag', options.tag, 'references green main commit', commit)
        output = os.environ.get('GITHUB_OUTPUT')
        if output and not options.remote_ref_only:
            with open(output, 'a', encoding='utf-8') as handle:
                handle.write('tag=' + options.tag + '\ncommit=' + commit + '\nversion=' + options.tag[1:] + '\n')
        return 0
    except (ReleaseError, ET.ParseError, OSError) as exc:
        print('RELEASE BLOCKED:', exc, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
