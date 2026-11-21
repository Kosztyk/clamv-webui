#!/usr/bin/env python3
"""Adversarial offline tests for coverage, the previous release and bounded dispatch."""
from pathlib import Path
from fractions import Fraction
import importlib.util,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
def load(path,name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/path);obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj
def must_reject(fn,reason):
    try:fn()
    except (ValueError,AssertionError):print('PASS: rejected '+reason);return
    raise AssertionError('Did not reject '+reason)

def main():
    cov=load('issue58-coverage-ratchet.py','cov58');up=load('issue58-resolve-predecessor.py','upgrade58')
    with tempfile.TemporaryDirectory() as td:
        p=Path(td);a=p/'before.xml';b=p/'after.xml'
        def write(file,covered,missed):file.write_text(f'<report><counter type="LINE" covered="{covered}" missed="{missed}" /></report>')
        write(a,35,65);write(b,39,61)
        assert cov.compare(a,b)==(Fraction(35,100),Fraction(39,100))
        write(b,30,70);must_reject(lambda:cov.compare(a,b),'coverage regression')
        b.write_text('<report />');must_reject(lambda:cov.compare(a,b),'missing JaCoCo line total')
        b.unlink();must_reject(lambda:cov.compare(a,b),'missing coverage artifact')
    with tempfile.TemporaryDirectory() as td:
        p=Path(td)
        def git(*args):
            x=subprocess.run(['git',*args],cwd=p,capture_output=True,text=True)
            assert x.returncode==0,x.stderr
            return x.stdout.strip()
        git('init','-q');git('config','user.email','ci@example.invalid');git('config','user.name','CI')
        commits=[]
        for i in range(3):
            (p/'a').write_text(str(i));git('add','.');git('commit','-qm',str(i));commits.append(git('rev-parse','HEAD'))
        git('tag','V2.0.0',commits[0]);git('tag','v2.1beta1',commits[1]);git('tag','v2.1.0-beta2',commits[2])
        releases=[{'tag_name':'V2.0.0','published_at':'2026-10-30T12:00:00Z','draft':False},
                  {'tag_name':'v2.1beta1','published_at':'2026-11-12T12:00:00Z','draft':False},
                  {'tag_name':'v2.1.0-beta2','published_at':'2026-11-14T12:00:00Z','draft':False}]
        assert up.select(releases,commits[2],'v2.1.0-beta2',p)=={'release_tag':'v2.1beta1','commit_sha':commits[1]}
        assert up.select(releases,commits[2],'v2.1.0-beta2',p,stable_only=True)['release_tag']=='V2.0.0'
        # A published beta may live on a different branch from the new candidate.
        git('checkout','-q','--detach',commits[0])
        (p/'a').write_text('independent new candidate')
        git('commit','-qam','branch off previous stable')
        divergent=git('rev-parse','HEAD')
        assert up.select(releases,divergent,'v2.1.0-beta2',p)['release_tag']=='v2.1beta1'
        assert up.select(releases,commits[1],root=p)['release_tag']=='V2.0.0'
        must_reject(lambda:up.select(releases,commits[0],root=p),'candidate without predecessor')
        must_reject(lambda:up.select(releases,'not-a-sha',root=p),'unverified commit')
        releases[0]['draft']=True
        must_reject(lambda:up.select(releases[:1],commits[2],root=p),'draft-only releases')
    worker=(ROOT/'src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java').read_text()
    assert 'new LinkedBlockingQueue<>(queueCapacity)' in worker
    assert 'catch (RejectedExecutionException full)' in worker
    assert 'locallyQueued.remove(jobId);' in worker
    assert 'scanMetrics.recordDispatchDeferred();' in worker
    print('PASS: issue #58 bounded queue, measurable coverage and dynamic predecessor selection')
if __name__=='__main__':main()
