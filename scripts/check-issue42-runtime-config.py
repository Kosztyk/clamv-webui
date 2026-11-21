#!/usr/bin/env python3
"""Issue #42: offline source/config privacy checks plus real Git clean-checkout simulation."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
count=0

def assert_case(message, result):
    global count
    assert result, message
    count+=1
    print(f"PASS {count}: {message}")

ignore=(root/'.gitignore').read_text()
settings=(root/'src/main/java/info/trizub/clamav/webclient/service/SettingsService.java').read_text()
encoder=(root/'src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationStore.java').read_text()
publish=(root/'publish-clamav-webui.sh').read_text()
audit=(root/'tools/audit-source-package.py').read_text()

runtime_file = 'conf/clamav-web-client.properties'
if (root/'.git').exists():
    runtime_in_source = bool(subprocess.check_output(['git','ls-files','--',runtime_file],cwd=root).strip())
else:
    runtime_in_source = (root/runtime_file).exists()
assert_case('runtime file excluded from source',not runtime_in_source)
assert_case('distribution ships safe example', (root/'conf/clamav-web-client.properties.example').exists())
assert_case('example contains no webhook credential line', 'app.webhook.url=' not in (root/'conf/clamav-web-client.properties.example').read_text())
assert_case('webhook URL is cataloged as encrypted secret', '?encrypt(' in ''.join(encoder.split()) and 'app.webhook.url' in (root/'src/main/resources/configuration-center/catalog.json').read_text())
assert_case('legacy plaintext is migrated and removed',
            'migrateLegacyWebhookUrl(props' in ''.join(settings.split()) and 'legacy.remove(WEBHOOK_URL)' in settings)
assert_case('no plaintext fallback when secret store unavailable', 'Encrypted Configuration Center is required to save secrets' in settings)
assert_case('persist removes URL before writing legacy file', 'props.remove(WEBHOOK_URL);' in settings)
assert_case('publisher rejects tracked runtime config', 'git ls-files --error-unmatch conf/clamav-web-client.properties' in publish)
assert_case('source auditor disallows shipping runtime config', "'clamav-web-client.properties'" in audit)
assert_case('runtime conf is explicitly ignored', '/conf/clamav-web-client.properties' in ignore and '/conf/integrations.properties' in ignore)

with tempfile.TemporaryDirectory(prefix='issue42-git-') as scratch:
    directory=Path(scratch)
    def git(*args, check=True):
        return subprocess.run(['git',*args], cwd=directory,check=check,capture_output=True,text=True)
    git('init','-q');git('config','user.email','issue42@example.invalid');git('config','user.name','Issue 42 Test')
    shutil.copy2(root/'.gitignore', directory/'.gitignore')
    (directory/'conf').mkdir()
    shutil.copy2(root/'conf/clamav-web-client.properties.example',directory/'conf/clamav-web-client.properties.example')
    git('add','.gitignore','conf/clamav-web-client.properties.example')
    git('commit','-qm','fresh source clone')
    for filename in ['clamav-web-client.properties','integrations.properties','configuration-center.properties','configuration-center.key','configuration-audit.jsonl']:
        (directory/'conf'/filename).write_text('app.webhook.url=https://example.test/SYNTHETIC-PRIVATE-TOKEN\n')
    assert_case('fresh clone remains clean with runtime generated config',not git('status','--porcelain').stdout.strip())
    assert_case('legacy and integration runtime files ignored',git('check-ignore','conf/clamav-web-client.properties','conf/integrations.properties').returncode==0)
    assert_case('example remains tracked',git('ls-files','conf/clamav-web-client.properties.example').stdout.strip()=='conf/clamav-web-client.properties.example')
    # Simulate GitHub's formerly tracked file, and remove it exactly as publisher does.
    git('add','-f','conf/clamav-web-client.properties');git('commit','-qm','old tracked runtime file')
    git('rm','-q','--cached','conf/clamav-web-client.properties')
    git('commit','-qm','untrack runtime file')
    assert_case('tracked-file removal leaves local settings untouched',(directory/'conf/clamav-web-client.properties').exists())
    assert_case('clean after untracking previously committed runtime config',not git('status','--porcelain').stdout.strip())

print(f'PASS: {count}/{count} issue #42 offline checks (git simulation; not an application boot)')
