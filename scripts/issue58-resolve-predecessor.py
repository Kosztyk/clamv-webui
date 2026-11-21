#!/usr/bin/env python3
"""Select the newest published, earlier release, then resolve its Git commit."""
from __future__ import annotations
import argparse,datetime as dt,json,os,re,subprocess,sys,urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TAG=re.compile(r'[vV][0-9][A-Za-z0-9.+_-]{1,63}\Z')
SHA=re.compile(r'[a-f0-9]{40}\Z')
REPO=re.compile(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z')
VERSION = re.compile(r'(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+)|((?:alpha|beta|rc)\d+))?(?:\+[0-9A-Za-z.-]+)?\Z')

def release_version(tag: str) -> tuple | None:
    match = VERSION.fullmatch(tag.lstrip('vV'))
    if not match:
        return None
    suffix = match[4] or match[5] or ''
    # The published v2.1beta1 tag predates the SemVer tag convention.
    suffix = re.sub(r'^([A-Za-z]+)(\d+)$', r'\1.\2', suffix)
    prerelease = tuple((0, int(part)) if part.isdigit() else (1, part)
                       for part in suffix.split('.')) if suffix else ()
    return (int(match[1]), int(match[2]), int(match[3] or 0), not suffix, prerelease)

def git(*args,cwd=ROOT):
    return subprocess.run(['git',*args],cwd=cwd,capture_output=True,text=True)

def fetch_releases(repo:str,token:str)->list[dict]:
    if not REPO.fullmatch(repo):raise ValueError('Invalid GitHub owner/repository name')
    releases=[]
    for page in range(1,21):
        req=urllib.request.Request(f'https://api.github.com/repos/{repo}/releases?per_page=100&page={page}',
             headers={'User-Agent':'clamav-upgrade-gate','Accept':'application/vnd.github+json',**({'Authorization':'Bearer '+token} if token else {})})
        with urllib.request.urlopen(req,timeout=30) as response:part=json.load(response)
        if not isinstance(part,list):raise ValueError('Malformed GitHub release API response')
        releases.extend(part)
        if len(part)<100:return releases
    raise ValueError('More than 2000 releases: refuse incomplete history')

def select(releases:list[dict],candidate_sha:str,candidate_tag:str='',root:Path=ROOT,stable_only:bool=False)->dict:
    if not SHA.fullmatch(candidate_sha) or git('cat-file','-e',candidate_sha+'^{commit}',cwd=root).returncode:
        raise ValueError('Candidate must be a locally available immutable commit')
    candidate_version = release_version(candidate_tag)
    if candidate_version is None and (root/'pom.xml').is_file():
        pom = ET.parse(root/'pom.xml').getroot()
        version = pom.findtext('{http://maven.apache.org/POM/4.0.0}version', '')
        candidate_version = release_version(version)
        if candidate_version is None:
            raise ValueError('Cannot decide released predecessor without a canonical POM version')
    eligible=[]
    for release in releases:
        tag=release.get('tag_name') or ''
        if release.get('draft') or not release.get('published_at') or tag==candidate_tag or not TAG.fullmatch(tag):continue
        version = release_version(tag)
        if version is None or (stable_only and (release.get('prerelease') or not version[3])):
            continue
        if candidate_version is not None and version >= candidate_version:
            continue
        result=git('rev-parse','--verify','refs/tags/'+tag+'^{commit}',cwd=root)
        if result.returncode:raise ValueError('Missing published tag in full checkout: '+tag)
        commit=result.stdout.strip()
        if not SHA.fullmatch(commit):raise ValueError('Invalid Git SHA for '+tag)
        if commit==candidate_sha:continue
        if candidate_version is None and git('merge-base','--is-ancestor',commit,candidate_sha,cwd=root).returncode:
            continue
        date=dt.datetime.fromisoformat(release['published_at'].replace('Z','+00:00'))
        eligible.append((date,version,tag,commit))
    if not eligible:raise ValueError('No eligible previous published release for this candidate')
    _,_,tag,commit=max(eligible)
    return {'release_tag':tag,'commit_sha':commit}

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo',default=os.environ.get('GITHUB_REPOSITORY','Kosztyk/clamv-webui'))
    ap.add_argument('--candidate-sha',default='')
    ap.add_argument('--candidate-tag',default=os.environ.get('GITHUB_REF_NAME',''))
    ap.add_argument('--stable-only', action='store_true', help='Select the previous stable API contract')
    ap.add_argument('--output',type=Path,default=ROOT/'artifacts/upgrade-predecessor.json')
    a=ap.parse_args()
    candidate=a.candidate_sha or git('rev-parse','HEAD').stdout.strip()
    chosen=select(fetch_releases(a.repo,os.environ.get('GITHUB_TOKEN','')),candidate,a.candidate_tag,
                  stable_only=a.stable_only)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(chosen,indent=2)+'\n')
    print('Verified previous published release:',chosen['release_tag'],chosen['commit_sha'])
if __name__=='__main__':
    try:main()
    except (ValueError,OSError) as exc:sys.exit('FAIL: predecessor resolution: '+str(exc))
