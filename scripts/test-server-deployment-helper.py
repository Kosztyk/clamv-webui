import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT
FAKE = '''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
a=sys.argv[1:]
with open(os.environ['DEPLOY_TEST_LOG'],'a') as f:f.write(json.dumps(a)+'\\n')
mode=os.environ['DEPLOY_TEST_MODE'];project=os.environ['DEPLOY_TEST_PROJECT']
if a[0]=='info':sys.exit(0)
if a[0]=='build':sys.exit(77 if mode=='build_failure' else 0)
if a[:2]==['image','inspect']:
 print('0:0' if mode=='wrong_user' else '10001:10001');sys.exit(0)
if a[0]=='start':sys.exit(0)
if a[0]=='inspect':
 if '--format' not in a:sys.exit(1 if mode=='fresh' else (0 if a[-1]=='clamav-gui' else 1))
 fmt=a[a.index('--format')+1]
 values={'com.docker.compose.project.working_dir':project,'com.docker.compose.project.config_files':project+'/docker-compose.yml','com.docker.compose.project':'existing_project','com.docker.compose.service':'clamav-web-client'}
 if '.State.Running' in fmt:print('true');sys.exit(0)
 for key,value in values.items():
  if key in fmt:print(value);sys.exit(0)
 sys.exit(2)
if a[0]=='compose':
 i=1
 while i<len(a) and a[i] in ('--project-directory','--project-name','-f'):i+=2
 command=a[i];args=a[i+1:]
 if command=='version':print('Docker Compose version v2');sys.exit(0)
 if command=='config':
  if '--services' in args:print('clamav-web-client\\nclamav-server')
  sys.exit(0)
 if command=='ps':
  if '-q' not in args:print('clamav-web-client running')
  sys.exit(0)
 if command=='run':
  if '--user' not in args:sys.exit(0 if mode=='already_owned' else 1)
  sys.exit(73 if mode=='permission_failure' else 0)
 if command in ('stop','up','logs','exec'):sys.exit(0)
sys.exit(9)
'''

results = []
for mode in ['existing', 'fresh', 'already_owned', 'build_failure', 'permission_failure', 'unmanaged_override', 'wrong_user', 'proxy_enabled', 'proxy_retained', 'proxy_disabled', 'legacy_proxy']:
    with tempfile.TemporaryDirectory(prefix='clamav-deploy-test-') as temporary:
        work = Path(temporary)
        source = work / 'source directory'
        project = work / 'existing project'
        binaries = work / 'bin'
        source.mkdir(); project.mkdir(); binaries.mkdir()
        for name in ['build-and-start.sh', 'docker-compose.yml']:
            shutil.copy2(APP / name, source / name)
        base = project / 'docker-compose.yml'
        base.write_text('services:\n  clamav-web-client:\n    image: previous-image\n')
        env_file = project / '.env'
        env_file.write_text('EXISTING_VALUE=keep-this-value\n')
        original = base.read_bytes(), env_file.read_bytes()
        override = project / 'clamv-webui-local-image.override.yml'
        if mode == 'unmanaged_override':override.write_text('# operator-owned file\n')
        if mode in ['legacy_proxy', 'proxy_disabled']:
            override.write_text('# Managed by ClamAV Web UI build-and-start.sh\nservices:\n  clamav-web-client:\n    environment:\n      SERVER_FORWARD_HEADERS_STRATEGY: \"framework\"\n')
        if mode == 'proxy_retained':
            override.write_text('# Managed by ClamAV Web UI build-and-start.sh\nservices:\n  clamav-web-client:\n    environment:\n      SERVER_FORWARD_HEADERS_STRATEGY: "native"\n      SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: "192.0.2.10/32"\n')
        docker = binaries / 'docker'
        docker.write_text(FAKE); docker.chmod(0o755)
        log = work / 'commands.jsonl'
        environment = dict(os.environ, PATH=str(binaries) + ':' + os.environ['PATH'],
                           DEPLOY_TEST_MODE=mode, DEPLOY_TEST_PROJECT=str(project), DEPLOY_TEST_LOG=str(log))
        environment.pop('CLAMAV_TRUSTED_PROXY_CIDRS', None)
        if mode == 'proxy_enabled':environment['CLAMAV_TRUSTED_PROXY_CIDRS']='192.0.2.10/32'
        options = ['--trusted-proxy'] if mode == 'proxy_enabled' else ['--direct-access'] if mode == 'proxy_disabled' else []
        run = subprocess.run(['bash', str(source/'build-and-start.sh'), *options], env=environment,
                             text=True, capture_output=True, timeout=15)
        commands = [json.loads(line) for line in log.read_text().splitlines()]
        stop = [c for c in commands if 'stop' in c]
        start = [c for c in commands if c and c[0] == 'start']
        ups = [c for c in commands if 'up' in c]
        root_runs = [c for c in commands if 'run' in c and '--user' in c]
        assert base.read_bytes() == original[0] and env_file.read_bytes() == original[1], mode
        if mode in ['existing', 'fresh', 'already_owned', 'proxy_enabled', 'proxy_retained', 'proxy_disabled']:
            assert run.returncode == 0, (mode, run.stdout, run.stderr)
            assert ups and '--no-build' in ups[-1], mode
            if mode != 'fresh':
                assert '--no-deps' in ups[-1] and 'existing_project' in ups[-1] and str(base) in ups[-1], mode
                assert commands.index(next(c for c in commands if c[0]=='build')) < commands.index(stop[0]), mode
            else:assert not stop and not start, mode
            assert bool(root_runs) == (mode != 'already_owned'), mode
            result_override = (source if mode=='fresh' else project) / override.name
            text = result_override.read_text()
            assert 'user: "10001:10001"' in text and 'pull_policy: never' in text, mode
            if mode in ['proxy_enabled', 'proxy_retained']:
                assert 'SERVER_FORWARD_HEADERS_STRATEGY: \"native\"' in text and 'SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: \"192.0.2.10/32\"' in text, mode
            if mode == 'proxy_disabled':
                assert 'SERVER_FORWARD_HEADERS_STRATEGY: \"none\"' in text and 'SERVER_FORWARD_HEADERS_STRATEGY: \"framework\"' not in text, mode
        elif mode == 'permission_failure':
            assert run.returncode != 0 and stop and root_runs and start and not ups, mode
        else:
            assert run.returncode != 0 and not stop and not root_runs and not ups, mode
        if mode == 'unmanaged_override':assert override.read_text() == '# operator-owned file\n'
        results.append({'scenario':mode,'result':'passed'})
        print('PASS:',mode)

(ROOT/'target').mkdir(exist_ok=True)
(ROOT/'target/server-deployment-helper-test-results.json').write_text(json.dumps({
    'method':'Actual Bash helper executed against a simulated Docker CLI; no Docker image/runtime execution',
    'scenarios':results
},indent=2)+'\n')
