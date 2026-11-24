#!/usr/bin/env python3
"""Block material regressions in measured JaCoCo line coverage.

CI compares coverage when application or Maven inputs change. A measured drop
greater than 0.01 percentage point fails the comparison. The Maven coverage floor
also applies to full builds that do not run this comparison.
"""
from pathlib import Path
from fractions import Fraction
import argparse,sys,xml.etree.ElementTree as ET

# 0.01 percentage point = 0.0001 as a ratio.
TOLERANCE = Fraction(1, 10_000)

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
    if old-new>TOLERANCE:
        raise ValueError(
            f'JaCoCo line coverage regressed beyond 0.01 percentage point tolerance: '
            f'{float(old)*100:.4f}% -> {float(new)*100:.4f}%'
        )
    return old,new

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--baseline',required=True,type=Path);ap.add_argument('--candidate',required=True,type=Path)
    a=ap.parse_args()
    try:
        old,new=compare(a.baseline,a.candidate)
        delta=float(new-old)*100
        print(f'PASS: coverage non-regression within 0.01 pp tolerance '
              f'{float(old)*100:.4f}% -> {float(new)*100:.4f}% ({delta:+.4f} pp)')
    except (ValueError,ET.ParseError) as exc:sys.exit('FAIL: '+str(exc))
