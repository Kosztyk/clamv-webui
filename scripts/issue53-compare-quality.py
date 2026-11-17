#!/usr/bin/env python3
"""Fail on NEW SpotBugs and PMD findings relative to the PR's trusted base.

Counts per (rule,class,member) prevent shifted line numbers from masking old
findings; the base and candidate must be analyzed using identical scanner rules.
Missing or malformed reports are a hard failure. New findings must be fixed,
not put on a permanent all-project exclusion list.
"""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def read_xml(path:Path) -> ET.Element:
    if not path.is_file() or not path.stat().st_size:
        raise ValueError(f'Missing or empty analysis report: {path}')
    return ET.parse(path).getroot()


def spotbugs(path:Path)->Counter:
    root=read_xml(path)
    if root.tag != 'BugCollection' or root.findall('file'):
        raise ValueError(f'Unexpected SpotBugs report format: {path}: {root.tag}')
    errors = root.find('Errors')
    if errors is not None and any(int(errors.get(field, '0')) for field in ('errors', 'missingClasses')):
        raise ValueError(f'SpotBugs reported incomplete analysis: {path}')
    findings=Counter()
    for b in root.findall('BugInstance'):
        cls=b.find('.//Class')
        method=b.find('.//Method')
        field=b.find('.//Field')
        typ=b.get('type') or b.get('abbrev') or '<unknown>'
        key=('SpotBugs',typ,cls.get('classname','') if cls is not None else '',
             method.get('name','') if method is not None else '',
             field.get('name','') if field is not None else '')
        findings[key]+=1
    return findings


def pmd(path:Path)->Counter:
    root=read_xml(path)
    if root.tag.split('}')[-1] != 'pmd':
        raise ValueError(f'Unexpected PMD report format: {path}: {root.tag}')
    if any(entry.tag.split('}')[-1] in {'error', 'configerror'} for entry in root):
        raise ValueError(f'PMD reported incomplete analysis: {path}')
    findings=Counter()
    for f in root:
        if f.tag.split('}')[-1] != 'file': continue
        filename=f.get('name','').replace('\\','/')
        # Worktree paths differ: compare the stable /src/ portion only.
        if '/src/' in filename: filename='src/'+filename.split('/src/',1)[1]
        for v in f:
            if v.tag.split('}')[-1] == 'violation':
                key=('PMD', v.get('rule',''), filename, v.get('method','') or v.get('class',''))
                findings[key]+=1
    return findings


def regressions(base:Counter,candidate:Counter)->list[tuple[tuple,int,int]]:
    return sorted((key, base.get(key,0),n) for key,n in candidate.items() if n>base.get(key,0))

def reviewed_findings(path: Path) -> Counter:
    findings = Counter()
    for row in json.loads(path.read_text(encoding='utf-8')):
        tool, *fingerprint, limit = row
        key = (tool, *fingerprint)
        if tool not in {'SpotBugs', 'PMD'} or key in findings or type(limit) is not int or limit < 1:
            raise ValueError(f'Invalid reviewed finding in {path}')
        findings[key] = limit
    return findings


def main()->None:
    ap=argparse.ArgumentParser()
    ap.add_argument('--baseline',type=Path)
    ap.add_argument('--candidate',type=Path,default=Path('target'))
    ap.add_argument('--reviewed-baseline', type=Path)
    args=ap.parse_args()
    if args.baseline is None and args.reviewed_baseline is None:
        ap.error('A comparison baseline is required')
    reviewed = reviewed_findings(args.reviewed_baseline) if args.reviewed_baseline else Counter()
    result=[]
    for name,tool,parser in (('spotbugsXml.xml','SpotBugs',spotbugs),('pmd.xml','PMD',pmd)):
        previous=parser(args.baseline/name) if args.baseline else Counter()
        accepted = Counter({key: count for key, count in reviewed.items() if key[0] == tool})
        current=parser(args.candidate/name)
        problems=regressions(previous | accepted,current)
        print(f'{name}: base {sum(previous.values())}, audited {sum(accepted.values())}, current {sum(current.values())}, regressions {len(problems)}')
        result.extend(problems)
    if result:
        for key,old,new in result:
            print(f'NEW: {key}: {old} -> {new}',file=sys.stderr)
        sys.exit(1)
    print('PASS: no new SpotBugs/FindSecBugs or PMD findings')
if __name__=='__main__':
    try: main()
    except (ValueError,ET.ParseError) as e:
        sys.exit(f'FAIL: {e}')
