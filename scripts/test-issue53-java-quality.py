#!/usr/bin/env python3
"""Issue #53 adversarial offline checks: coverage and new static debt cannot be hidden."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
from collections import Counter
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]

def load(name, filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

checker=load('java_quality_checker','check-issue53-java-quality.py')
compare=load('java_quality_compare','issue53-compare-quality.py')
prepare=load('java_quality_prepare','issue53-prepare-baseline.py')

def should_fail(call,label):
    try:call()
    except (AssertionError,ValueError,ET.ParseError): print('PASS:',label);return
    raise AssertionError('Expected blocked regression: '+label)

def main():
    pom=ROOT/'pom.xml'; ci=ROOT/'.github/workflows/ci.yml';rel=ROOT/'.github/workflows/release.yml';gate=ROOT/'scripts/ci-evaluate-gate.py'
    checker.verify(pom,ci,rel,gate)
    with tempfile.TemporaryDirectory() as d:
        t=Path(d)
        a=t/'pom.xml';a.write_text(pom.read_text().replace('<jacoco.minimum.line.coverage>0.82</jacoco.minimum.line.coverage>','<jacoco.minimum.line.coverage>0.00</jacoco.minimum.line.coverage>'))
        should_fail(lambda:checker.verify(a,ci,rel,gate),'Coverage threshold removed -> rejected')
        b=t/'ci.yml';b.write_text(ci.read_text().replace('    needs: [changes, static, api-contract, java-tests, java-quality,','    needs: [changes, static, api-contract, java-tests,'))
        should_fail(lambda:checker.verify(pom,b,rel,gate),'Java quality not required by gate -> rejected')
        d=t/'release.yml';d.write_text(rel.read_text().replace('-Dspotless.ratchet.ref=HEAD^', '-Dspotless.ratchet.ref=HEAD'))
        should_fail(lambda:checker.verify(pom,ci,d,gate),'Release formatter ratcheted to HEAD is a no-op')
        b.write_text(ci.read_text().replace('spotless:check','spotless:apply'))
        should_fail(lambda:checker.verify(pom,b,rel,gate),'Formatter changed from check to apply -> rejected')
        c=t/'base.xml';e=t/'candidate.xml';p0=t/'pmd-base.xml';p1=t/'pmd-candidate.xml'
        c.write_text('<BugCollection><BugInstance type="SECURITY_SQL"><Class classname="app.Foo"/><Method name="go"/></BugInstance></BugCollection>')
        e.write_text('<BugCollection><BugInstance type="SECURITY_SQL"><Class classname="app.Foo"/><Method name="go"/></BugInstance><BugInstance type="SECURITY_SQL"><Class classname="app.Foo"/><Method name="go"/></BugInstance></BugCollection>')
        p0.write_text('<pmd><file name="/tmp/base/src/main/java/app/Foo.java"><violation rule="BadPractice" beginline="10" /></file></pmd>')
        p1.write_text('<pmd><file name="/tmp/candidate/src/main/java/app/Foo.java"><violation rule="BadPractice" beginline="20" /></file></pmd>')
        assert compare.regressions(compare.spotbugs(c),compare.spotbugs(e)), 'New duplicate finding must fail'
        assert not compare.regressions(compare.pmd(p0),compare.pmd(p1)), 'Moved existing violation is not new debt'
        print('PASS: synthetic new SpotBugs finding blocked; moved legacy PMD finding tolerated')
        e.write_text('<BugCollection><BugInstance type="SECURITY_SQL"><Class classname="app.Foo"/><Method name="go"/></BugInstance></BugCollection>')
        assert not compare.regressions(compare.spotbugs(c),compare.spotbugs(e))
        assert sum(compare.spotbugs(e).values())==1
        p1.write_text('<pmd><file name="/tmp/candidate/src/main/java/app/Foo.java"><violation rule="BadPractice" beginline="20" /><violation rule="BadPractice" beginline="25" /></file></pmd>')
        assert compare.regressions(compare.pmd(p0),compare.pmd(p1)), 'Added PMD violation must fail'
        print('PASS: new PMD violation blocked; unchanged baseline allowed')
        e.unlink()
        should_fail(lambda:compare.spotbugs(e),'Missing report does not quietly pass')
        old='<project><profiles><profile><id>old-profile</id></profile></profiles></project>'
        merged=prepare.inject(pom.read_text(),old)
        assert '<id>ci-quality</id>' in merged and '<id>old-profile</id>' in merged
        print('PASS: historical POM receives identical scanner profile without touching original')
        mixed = '<project><build><resources/><plugins/></build><profiles><profile><id>db-integration</id><build><plugins/></build></profile></profiles></project>'
        # Actual predecessor-style POMs have top-level <resources> before
        # <plugins> and a nested database integration profile.
        mixed = mixed.replace('<plugins/>', '<plugins></plugins>')
        tree = ET.fromstring(prepare.inject(pom.read_text(), mixed))
        assert any(plugin.tag.rsplit('}', 1)[-1]=='plugin' and any(
            el.tag.rsplit('}', 1)[-1]=='artifactId' and el.text=='jacoco-maven-plugin'
            for el in plugin) for plugin in tree.find('build/plugins'))
        # Subsequent PRs have ci-quality already present on their base branch.
        # Replacing old scanner versions is mandatory: comparing unlike scanner
        # versions makes the "only new findings" gate untrustworthy.
        stale = merged.replace('<version>3.4.0</version>', '<version>0.0.1</version>')
        assert stale != merged, 'Expected test to alter the Spotless baseline version'
        refreshed = prepare.inject(pom.read_text(), stale)
        assert refreshed == merged, 'Existing baseline profile was not updated to candidate scanner versions'
        assert refreshed.count('<id>ci-quality</id>') == 1, 'Duplicate quality profile injected'
        print('PASS: post-issue53 baseline with an existing ci-quality profile is refreshed in place')
        duplicate = stale.replace('</profiles>', prepare.RE.search(pom.read_text()).group(1)+'\n</profiles>', 1)
        should_fail(lambda: prepare.inject(pom.read_text(),duplicate), 'Duplicate ci-quality baseline profiles rejected')
    print('Issue #53 negative quality policy tests passed')

if __name__=='__main__':main()
