#!/usr/bin/env python3
"""Exercise the real publisher with local bare Git remotes and an offline gh fixture."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import warnings
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PUBLISH=ROOT/'publish-clamav-webui.sh'
REPOSITORY='example-owner/example-repo'
FIXTURE={
 'pom.xml':'<project><version>2.1.0</version></project>\n',
 'Dockerfile':'# Synthetic publisher fixture only\n',
 'README.md':'Synthetic complete-source transfer fixture.\n',
 'docker-compose.minimal.yaml':'services: {}\n',
 'docker-compose.environment.yaml':'services: {}\n',
 'docs/CONFIGURATION_FIELD_AUDIT.md':'Synthetic audit fixture.\n',
 'src/main/resources/application.properties':'synthetic.fixture=true\n',
 'publish-clamav-webui.sh':'# Synthetic archived placeholder; the real publisher is invoked separately.\n'}


class PublishingTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='publisher-test-');self.addCleanup(self.temporary.cleanup)
        self.work=Path(self.temporary.name);(self.work/'bin').mkdir();(self.work/'tmp').mkdir()
        self.events=self.work/'events.jsonl';self.remote=self.work/'remote.git';self.seed=self.work/'seed'
        self.env=os.environ.copy();self.env.update({'GIT_CONFIG_GLOBAL':os.devnull,'GIT_CONFIG_NOSYSTEM':'1',
            'GIT_TERMINAL_PROMPT':'0','GIT_CONFIG_COUNT':'1','GIT_CONFIG_KEY_0':f'url.file://{self.remote}.insteadOf',
            'GIT_CONFIG_VALUE_0':f'https://github.com/{REPOSITORY}.git','PUBLISH_TEST_EVENTS':str(self.events),
            'TMPDIR':str(self.work/'tmp'),'PATH':str(self.work/'bin')+os.pathsep+os.environ['PATH'],
            'GH_TOKEN':'','GITHUB_TOKEN':'','PUBLISH_TEST_BASE':'main'})
        gh=self.work/'bin/gh'
        gh.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
event={'args':args}
if args[:2]==['pr','create']:
 event['body']=Path(args[args.index('--body-file')+1]).read_text()
with open(os.environ['PUBLISH_TEST_EVENTS'],'a') as output:output.write(json.dumps(event)+'\\n')
if args[:2]==['auth','status'] or args[:2]==['auth','setup-git']:pass
elif args[:3]==['api','--include','user']:print('X-OAuth-Scopes: repo,workflow\\n\\n{}')
elif args[:2]==['repo','view']:print(os.environ['PUBLISH_TEST_BASE'])
elif args[:2]==['api','user']:print('example-user 123456')
elif args and args[0]=='api' and args[1].startswith('repos/'):print('true')
elif args[:2]==['pr','create']:print('https://github.com/example-owner/example-repo/pull/1')
else:sys.exit('Unexpected gh invocation')
''');gh.chmod(0o700)
        self.git('init','--bare',str(self.remote));self.git('init','-b','main',str(self.seed))
        self.git('-C',str(self.seed),'config','user.name','Synthetic Fixture');self.git('-C',str(self.seed),'config','user.email','fixture@example.test')
        (self.seed/'README.md').write_text('Previous source.\n');(self.seed/'obsolete.txt').write_text('Obsolete source.\n')
        self.git('-C',str(self.seed),'add','.');self.git('-C',str(self.seed),'commit','-m','Synthetic baseline')
        self.git('-C',str(self.seed),'remote','add','origin',str(self.remote));self.git('-C',str(self.seed),'push','origin','main')
        self.git('--git-dir',str(self.remote),'symbolic-ref','HEAD','refs/heads/main')
    def git(self,*args):
        return subprocess.check_output(['git',*args],env=self.env,text=True,stderr=subprocess.PIPE).strip()
    def archive(self,extra=None,tamper=False,duplicate=False,symlink=False):
        files=dict(FIXTURE);files.update(extra or {})
        manifest=''.join(hashlib.sha256(value.encode()).hexdigest()+'  '+name+'\n' for name,value in sorted(files.items()))
        archive=self.work/'full source (1).zip'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as output:
                for name,value in files.items():output.writestr('clamv-webui-v2.1.0/'+name,value+'changed' if tamper and name=='README.md' else value)
                output.writestr('clamv-webui-v2.1.0/SOURCE_MANIFEST.sha256',manifest)
                if duplicate:output.writestr('clamv-webui-v2.1.0/README.md','duplicate')
                if symlink:
                    item=zipfile.ZipInfo('clamv-webui-v2.1.0/link');item.create_system=3;item.external_attr=(0o120777<<16);output.writestr(item,'../../outside')
        return archive
    def run_publisher(self,archive,*flags,repo=REPOSITORY):
        return subprocess.run(['bash',str(getattr(self,'publisher',PUBLISH)),str(archive),repo,*flags],env=self.env,cwd=self.work,
            text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45)
    def bootstrap_fixture(self,platform='Darwin',architecture='arm64'):
        """Real bootstrap logic with an offline asset, synthetic digest and home.

        Only the temporary script copy's Mac asset pins and home-path prefix
        change. Production pins stay enforced; the account's HOME is untouched.
        """
        gh=self.work/'bin/gh';source=gh.read_text();gh.unlink()
        source=source.replace("event={'args':args}","event={'args':args,'executable':sys.argv[0]}")
        source=source.replace("if args[:2]==['auth','status'] or args[:2]==['auth','setup-git']:pass",
            "if args==['--version']:print('gh version synthetic-bootstrap-fixture')\nelif args[:2]==['auth','status'] or args[:2]==['auth','setup-git']:pass")
        self.gh_source=source;self.fake_home=self.work/'user home';self.fake_home.mkdir()
        (self.fake_home/'.local').write_text('Blocked legacy parent; do not change this file.\n')
        asset=self.work/'synthetic-gh.zip'
        with zipfile.ZipFile(asset,'w') as archive:
            member=zipfile.ZipInfo('gh_fixture/bin/gh');member.create_system=3;member.external_attr=(0o100700<<16)
            archive.writestr(member,source)
        self.gh_asset=asset
        digest=hashlib.sha256(asset.read_bytes()).hexdigest();script=PUBLISH.read_text()
        for expected in ['d700207eae9ae98fe2b89535c2c087c07823d4628c56364695a5a07ef311c062',
                         'a2f86418f4350ae6e9ec60d5000bdf7665728436946a97fc27fe50e6ae752682']:
            self.assertEqual(script.count(expected),1);script=script.replace(expected,digest)
        script=script.replace('$HOME/','$PUBLISH_TEST_HOME/')
        self.publisher=self.work/'publisher-under-test.sh';self.publisher.write_text(script)
        # Isolate PATH so an installed host gh cannot bypass bootstrap coverage.
        for tool in ['bash','python3','git','unzip','awk','sort','uniq','wc','tr','mktemp','cp','mv','chmod','rm','mkdir','dirname','basename','date','tar','cat','sed','head',
                     'shasum' if shutil.which('shasum') else 'sha256sum']:
            executable=shutil.which(tool);self.assertIsNotNone(executable,tool)
            (self.work/'bin'/tool).symlink_to(executable)
        for name,body in {
            'uname':"import os,sys\nprint(os.environ['PUBLISH_TEST_OS'] if sys.argv[1:] == ['-s'] else os.environ['PUBLISH_TEST_ARCH'])\n",
            'curl':"import os,sys,shutil\nif os.environ.get('PUBLISH_TEST_FORBID_CURL'):sys.exit('Unexpected download')\nargs=sys.argv[1:];shutil.copyfile(os.environ['PUBLISH_TEST_ASSET'],args[args.index('-o')+1])\n"}.items():
            file=self.work/'bin'/name;file.write_text('#!/usr/bin/env python3\n'+body);file.chmod(0o700)
        self.env.update(PATH=str(self.work/'bin'),PUBLISH_TEST_HOME=str(self.fake_home),PUBLISH_TEST_OS=platform,
            PUBLISH_TEST_ARCH=architecture,PUBLISH_TEST_ASSET=str(asset))
        self.env.pop('XDG_CACHE_HOME',None)
    def seed_cached_gh(self,directory):
        file=directory/'bin/gh';file.parent.mkdir(parents=True);file.write_text(self.gh_source);file.chmod(0o700);return file
    def test_uncached_macos_cli_uses_native_cache_for_both_architectures_with_blocked_legacy_parent(self):
        self.bootstrap_fixture()
        for architecture,label in [('arm64','arm64'),('x86_64','amd64')]:
            with self.subTest(architecture=architecture):
                self.env['PUBLISH_TEST_ARCH']=architecture
                result=self.run_publisher(self.archive(),'--dry-run');self.assertEqual(result.returncode,0,result.stdout)
                cached=self.fake_home/f'Library/Caches/clamav-publisher/gh-2.103.3-macOS-{label}/bin/gh'
                self.assertTrue(cached.is_file());self.assertEqual(cached.read_text(),self.gh_source)
                self.assertTrue(os.access(cached,os.X_OK));self.assertTrue((self.fake_home/'.local').is_file())
    def test_macos_cached_cli_is_reused_without_download(self):
        self.bootstrap_fixture();self.seed_cached_gh(self.fake_home/'Library/Caches/clamav-publisher/gh-2.103.3-macOS-arm64')
        self.env['PUBLISH_TEST_FORBID_CURL']='1';result=self.run_publisher(self.archive(),'--dry-run')
        self.assertEqual(result.returncode,0,result.stdout);self.assertNotIn('Installing GitHub CLI',result.stdout)
    def test_linux_cached_cli_honors_absolute_xdg_cache_with_spaces(self):
        self.bootstrap_fixture('Linux','x86_64');cache=self.work/'custom cache'
        self.env.update(XDG_CACHE_HOME=str(cache),PUBLISH_TEST_FORBID_CURL='1')
        self.seed_cached_gh(cache/'clamav-publisher/gh-2.103.3-linux-amd64')
        result=self.run_publisher(self.archive(),'--dry-run');self.assertEqual(result.returncode,0,result.stdout)
    def test_linux_default_cache_uses_dot_cache_without_a_local_share_write(self):
        self.bootstrap_fixture('Linux','x86_64');self.env['PUBLISH_TEST_FORBID_CURL']='1'
        self.seed_cached_gh(self.fake_home/'.cache/clamav-publisher/gh-2.103.3-linux-amd64')
        result=self.run_publisher(self.archive(),'--dry-run');self.assertEqual(result.returncode,0,result.stdout)
        self.assertTrue((self.fake_home/'.local').is_file())
    def test_relative_xdg_cache_is_ignored(self):
        self.bootstrap_fixture('Linux','x86_64');self.env.update(XDG_CACHE_HOME='relative-cache',PUBLISH_TEST_FORBID_CURL='1')
        self.seed_cached_gh(self.fake_home/'.cache/clamav-publisher/gh-2.103.3-linux-amd64')
        result=self.run_publisher(self.archive(),'--dry-run');self.assertEqual(result.returncode,0,result.stdout)
        self.assertFalse((self.work/'relative-cache').exists())
    def test_blocked_native_cache_uses_private_temporary_cli_and_scoped_git_credentials(self):
        self.bootstrap_fixture();(self.fake_home/'Library').write_text('Blocked native cache parent.\n')
        result=self.run_publisher(self.archive());self.assertEqual(result.returncode,0,result.stdout)
        self.assertIn('private temporary GitHub CLI',result.stdout);self.assertions_for_snapshot()
        events=[json.loads(line) for line in self.events.read_text().splitlines()]
        self.assertFalse(any(event['args'][:2]==['auth','setup-git'] for event in events))
        installed={Path(event['executable']) for event in events}
        self.assertTrue(installed)
        for path in installed:self.assertIn('/gh-cli/bin/gh',str(path));self.assertFalse(path.exists())
    def test_download_checksum_failure_does_not_install_or_authenticate(self):
        self.bootstrap_fixture();self.gh_asset.write_bytes(self.gh_asset.read_bytes()+b'tampered')
        result=self.run_publisher(self.archive(),'--dry-run');self.assertNotEqual(result.returncode,0)
        self.assertIn('checksum mismatch',result.stdout);self.assertFalse(self.events.exists())
        self.assertFalse((self.fake_home/'Library/Caches/clamav-publisher/gh-2.103.3-macOS-arm64/bin/gh').exists())
    def assertions_for_snapshot(self,ref='main'):
        files=set(self.git('--git-dir',str(self.remote),'ls-tree','-r','--name-only',ref).splitlines())
        self.assertEqual(files,set(FIXTURE)|{'SOURCE_MANIFEST.sha256'})
        self.assertNotIn('obsolete.txt',files)
        self.assertEqual(self.git('--git-dir',str(self.remote),'rev-list','--count',ref),'2')
        self.assertEqual(self.git('--git-dir',str(self.remote),'show',f'{ref}:README.md'),FIXTURE['README.md'].strip())
    def test_offline_check_uses_no_github_or_authentication(self):
        result=self.run_publisher(self.archive(),'--check');self.assertEqual(result.returncode,0,result.stdout)
        self.assertIn('validation passed',result.stdout);self.assertFalse(self.events.exists())
    def test_direct_push_preserves_history_and_replaces_the_complete_tracked_snapshot(self):
        result=self.run_publisher(self.archive());self.assertEqual(result.returncode,0,result.stdout)
        self.assertIn('SUCCESS:',result.stdout);self.assertions_for_snapshot()
        self.assertEqual(self.git('--git-dir',str(self.remote),'log','-1','--format=%ae'),'123456+example-user@users.noreply.github.com')
    def test_dry_run_creates_no_commit_or_remote_reference(self):
        before=self.git('--git-dir',str(self.remote),'show-ref');result=self.run_publisher(self.archive(),'--dry-run')
        self.assertEqual(result.returncode,0,result.stdout);self.assertEqual(before,self.git('--git-dir',str(self.remote),'show-ref'))
        self.assertIn('Dry run passed',result.stdout)
    def test_repeat_update_detects_the_already_published_source(self):
        archive=self.archive();self.assertEqual(self.run_publisher(archive).returncode,0)
        before=self.git('--git-dir',str(self.remote),'show-ref');result=self.run_publisher(archive)
        self.assertEqual(result.returncode,0,result.stdout);self.assertIn('No push needed',result.stdout)
        self.assertEqual(before,self.git('--git-dir',str(self.remote),'show-ref'))
    def test_protected_default_branch_falls_back_to_a_real_update_branch_and_pr(self):
        hook=self.remote/'hooks/update';hook.write_text('#!/bin/sh\n[ "$1" != refs/heads/main ]\n');hook.chmod(0o700)
        before=self.git('--git-dir',str(self.remote),'rev-parse','main');result=self.run_publisher(self.archive())
        self.assertEqual(result.returncode,0,result.stdout);self.assertEqual(before,self.git('--git-dir',str(self.remote),'rev-parse','main'))
        branches=self.git('--git-dir',str(self.remote),'for-each-ref','--format=%(refname:short)','refs/heads').splitlines()
        update=next(b for b in branches if b!='main');self.assertions_for_snapshot(update)
        events=[json.loads(line) for line in self.events.read_text().splitlines()]
        self.assertTrue(any(event['args'][:2]==['pr','create'] and '107-field audit' in event.get('body','') for event in events))
    def test_explicit_pr_mode_leaves_default_branch_untouched(self):
        before=self.git('--git-dir',str(self.remote),'rev-parse','main');result=self.run_publisher(self.archive(),'--pull-request')
        self.assertEqual(result.returncode,0,result.stdout);self.assertEqual(before,self.git('--git-dir',str(self.remote),'rev-parse','main'))
        self.assertIn('pull request is open',result.stdout)
    def test_rejected_branch_push_exits_with_failure_instead_of_claiming_success(self):
        hook=self.remote/'hooks/update';hook.write_text('#!/bin/sh\nexit 1\n');hook.chmod(0o700)
        result=self.run_publisher(self.archive());self.assertNotEqual(result.returncode,0)
        self.assertIn('rejected the update branch',result.stdout);self.assertNotIn('SUCCESS:',result.stdout)
    def test_default_branch_is_discovered_instead_of_assuming_main(self):
        self.git('--git-dir',str(self.remote),'branch','-m','main','trunk');self.git('--git-dir',str(self.remote),'symbolic-ref','HEAD','refs/heads/trunk')
        self.env['PUBLISH_TEST_BASE']='trunk';result=self.run_publisher(self.archive())
        self.assertEqual(result.returncode,0,result.stdout);self.assertions_for_snapshot('trunk')
    def test_tampered_file_is_rejected_before_authentication(self):
        result=self.run_publisher(self.archive(tamper=True));self.assertNotEqual(result.returncode,0)
        self.assertIn('SHA-256 validation failed',result.stdout);self.assertFalse(self.events.exists())
    def test_duplicate_zip_members_are_rejected_before_authentication(self):
        result=self.run_publisher(self.archive(duplicate=True));self.assertNotEqual(result.returncode,0)
        self.assertIn('Duplicate ZIP members',result.stdout);self.assertFalse(self.events.exists())
    def test_path_traversal_and_runtime_credentials_are_rejected(self):
        for name in ['../outside','conf/configuration-recovery-token','conf/secret.key','.env.production','certificates/example.key']:
            with self.subTest(name=name):
                result=self.run_publisher(self.archive(extra={name:'SYNTHETIC_DO_NOT_PUBLISH'}));self.assertNotEqual(result.returncode,0)
                self.assertFalse(self.events.exists())
    def test_symbolic_links_are_rejected_before_extraction_or_authentication(self):
        result=self.run_publisher(self.archive(symlink=True));self.assertNotEqual(result.returncode,0)
        self.assertIn('Symbolic links',result.stdout);self.assertFalse(self.events.exists())

if __name__=='__main__':unittest.main(verbosity=2)
