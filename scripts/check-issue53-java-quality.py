#!/usr/bin/env python3
"""Offline contract checks for issue #53: fail closed on weakened Java quality gates."""
from __future__ import annotations
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
NS={'m':'http://maven.apache.org/POM/4.0.0'}

def require(ok,msg):
    if not ok: raise AssertionError(msg)

def verify(pom:Path,ci:Path,release:Path,gate:Path):
    project=ET.parse(pom).getroot()
    def text(path):
        item=project.find(path,NS)
        return (item.text or '').strip() if item is not None else ''
    require(text('m:properties/m:java.version')=='21','JDK 21 application bytecode must remain supported')
    require(text('m:properties/m:jacoco.minimum.line.coverage') and
            float(text('m:properties/m:jacoco.minimum.line.coverage'))>=0.82,
            'JaCoCo coverage minimum must be at least the measured 82% floor')
    plugins=project.findall('m:build/m:plugins/m:plugin',NS)
    def artifact(plugins,name):
        return next((el for el in plugins if el.findtext('m:artifactId',namespaces=NS)==name),None)
    enforcer=artifact(plugins,'maven-enforcer-plugin')
    require(enforcer is not None,'Maven Enforcer must be active in normal verify')
    xml=ET.tostring(enforcer,encoding='unicode')
    for rule in ('requireMavenVersion','requireJavaVersion','banDuplicatePomDependencyVersions','requireReleaseDeps'):
        require(rule in xml,f'Missing build enforcer rule: {rule}')
    jacoco=artifact(plugins,'jacoco-maven-plugin')
    require(jacoco is not None,'JaCoCo must be active in normal verify')
    j=ET.tostring(jacoco,encoding='unicode')
    require('prepare-agent' in j and 'report' in j and 'check' in j and 'COVEREDRATIO' in j,
            'JaCoCo must instrument tests, report and check coverage at verify')
    surefire=artifact(plugins,'maven-surefire-plugin')
    require(surefire is not None and '@{argLine}' in ET.tostring(surefire,encoding='unicode'),
            'Mockito agent and JaCoCo must coexist using late argLine expansion')
    profile=next((x for x in project.findall('m:profiles/m:profile',NS)
                  if x.findtext('m:id',namespaces=NS)=='ci-quality'),None)
    require(profile is not None,'ci-quality profile is required')
    qp=profile.findall('m:build/m:plugins/m:plugin',NS)
    spotless=artifact(qp,'spotless-maven-plugin')
    spot=artifact(qp,'spotbugs-maven-plugin')
    pmd=artifact(qp,'maven-pmd-plugin')
    quality_enforcer=artifact(qp,'maven-enforcer-plugin')
    require(all(x is not None for x in (spotless,spot,pmd,quality_enforcer)),
            'Missing Spotless/SpotBugs/PMD/dependency-convergence plugin')
    fm=ET.tostring(spotless,encoding='unicode')
    require('spotless.ratchet.ref' in fm and 'googleJavaFormat' in fm and 'src/main/java' in fm and
            'src/test/java' in fm, 'Spotless must use a fixed upstream ratchet and include source and tests')
    bugs=ET.tostring(spot,encoding='unicode')
    require('findsecbugs-plugin' in bugs and 'spotbugs.xml' in bugs and 'spotbugs' in bugs and
            'failOnError' in bugs,'SpotBugs and FindSecBugs XML must be generated, failing on scanner errors')
    require('dependencyConvergence' in ET.tostring(quality_enforcer,encoding='unicode'),
            'Dependency convergence must execute in CI quality profile')
    rules=ET.tostring(pmd,encoding='unicode')
    require('errorprone.xml' in rules and 'bestpractices.xml' in rules and '<' in rules,
            'PMD ruleset and report required')
    wf=ci.read_text()
    try:
        import yaml
    except ImportError as ex: raise RuntimeError('PyYAML required') from ex
    data=yaml.load(wf,Loader=yaml.BaseLoader)
    jobs=data['jobs']
    require('java-quality' in jobs,'Dedicated quality CI job required')
    qjob=jobs['java-quality']
    require("needs.changes.outputs.java == 'true'" in qjob['if'],
            'Java quality job must be selected by changes')
    jr=jobs['java-tests']
    require(set(jr['strategy']['matrix']['jdk'])=={'21','25'},
            'Test Java 21 runtime and Java 25 forward-compatibility, not unsupported Java 17')
    require('java-quality' in jobs['gate']['needs'],'Required gate must depend on java-quality')
    require('java-quality' in gate.read_text() and "'java-quality': 'java'" in gate.read_text(),
            'Gate evaluator must fail on required quality job failures')
    steps='\n'.join(str(x.get('run','')) for x in qjob['steps'])
    for needle in ('-Pci-quality','spotless:check','scripts/issue53-compare-quality.py',
                   'scripts/issue53-prepare-baseline.py','git worktree add','-Denforcer.skip=true'):
        require(needle in steps,f'CI Java quality baseline or formatter check missing: {needle}')
    require('java-quality-spotbugs-pmd' in wf and 'target/site/jacoco/' in wf and
            'target/spotbugs.xml' in wf and 'target/pmd.xml' in wf,
            'Quality and coverage artifacts must be uploaded')
    rel=release.read_text()
    require('-Pci-quality' in rel and 'spotless:check' in rel and '-Dspotless.ratchet.ref=HEAD^' in rel,
            'Release job must re-run quality profile and Spotless')
    print('PASS: Java 21 + 25 test matrix, Spotless, SpotBugs/FindSecBugs, PMD, JaCoCo, Enforcer, release and required CI gate')

def main():
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument('--pom',type=Path,default=ROOT/'pom.xml')
    ap.add_argument('--ci',type=Path,default=ROOT/'.github/workflows/ci.yml')
    ap.add_argument('--release',type=Path,default=ROOT/'.github/workflows/release.yml')
    ap.add_argument('--gate',type=Path,default=ROOT/'scripts/ci-evaluate-gate.py')
    a=ap.parse_args()
    try: verify(a.pom,a.ci,a.release,a.gate)
    except (AssertionError,ValueError,KeyError,ET.ParseError,RuntimeError) as e:
        sys.exit(f'FAIL: {e}')
if __name__=='__main__': main()
