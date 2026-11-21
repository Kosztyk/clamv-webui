#!/usr/bin/env python3
"""Block regressions in measured JaCoCo line coverage between immutable Git base and candidate."""
from pathlib import Path
from fractions import Fraction
import argparse,sys,xml.etree.ElementTree as ET

def ratio(path:Path)->Fraction:
    if not path.is_file() or not path.stat().st_size:raise ValueError('Missing JaCoCo XML: '+str(path))
    root=ET.parse(path).getroot()
    counters=[x for x in root.findall('counter') if x.get('type')=='LINE']
    if root.tag!='report' or len(counters)!=1:raise ValueError('Missing JaCoCo total LINE counter: '+str(path))
    c=int(counters[0].get('covered','-1'));m=int(counters[0].get('missed','-1'))
    if c<0 or m<0 or c+m==0:raise ValueError('Invalid JaCoCo totals')
    return Fraction(c,c+m)

def compare(base:Path,current:Path)->tuple[Fraction,Fraction]:
    old,new=ratio(base),ratio(current)
    if new<old:raise ValueError(f'JaCoCo line coverage regressed: {float(old)*100:.2f}% -> {float(new)*100:.2f}%')
    return old,new

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--baseline',required=True,type=Path);ap.add_argument('--candidate',required=True,type=Path)
    a=ap.parse_args()
    try:
        old,new=compare(a.baseline,a.candidate)
        print(f'PASS: coverage non-regression {float(old)*100:.2f}% -> {float(new)*100:.2f}%')
    except (ValueError,ET.ParseError) as exc:sys.exit('FAIL: '+str(exc))
