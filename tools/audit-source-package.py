#!/usr/bin/env python3
"""Check a complete source tree/ZIP for runtime state, credentials and optional private identifiers.

Only filenames and rule labels are reported. --deny-file accepts a private JSON
object with string lists `literals` and `patterns`; that input is never bundled.
Without it, the reusable checks detect runtime files and common credential forms.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import zipfile

RUNTIME_NAMES={'.env','clamav-web-client.properties','configuration-center.properties','configuration-recovery-token',
    'configuration-audit.jsonl','configuration-database-state.properties','configuration-database-choice.properties',
    'integrations.properties','bootstrap-admin-password','auth-settings.key','id_rsa','id_ed25519'}
# RFC1918 dotted IPv4 literals must not be embedded into published source.
# Only two explicit security regression fixtures deliberately exercise non-public
# address rejection. Their byte tuples are documented here without reproducing
# private addresses as release-documentation literals.
_IPV4=re.compile(rb'(?<![A-Za-z0-9_.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![A-Za-z0-9_.])')
_RFC1918_FIXTURES={
    'src/test/java/info/trizub/clamav/webclient/service/WebhookAddressPolicyTest.java': {
        (10,1,2,3), (172,16,1,1), (172,31,255,254), (192,168,10,20),
    },
    'src/test/java/info/trizub/clamav/webclient/web/UiSmokeTest.java': {
        (10,77,88,10),
    },
}


def private_ipv4_evidence(name, data):
    """Return one error if an unapproved RFC1918 address is present (never the IP)."""
    allowed=_RFC1918_FIXTURES.get(name,set())
    for match in _IPV4.finditer(data):
        try: octets=tuple(int(octet) for octet in match.group().split(b'.'))
        except ValueError: continue
        if any(value>255 for value in octets):continue
        a,b,_,_=octets
        is_private=(a==10 or a==172 and 16<=b<=31 or a==192 and b==168)
        if not is_private:continue
        if octets in allowed:continue
        # Certain third-party SVG flag paths consist only of drawing coordinates:
        # SVG path decimals can accidentally look like dotted IP addresses.
        # Ignore such matches ONLY in bundled country-flag art, and ONLY when
        # the first octet is 10; 172.x and 192.168.x remain actionable.
        if name.startswith('src/main/resources/static/flags/') and name.endswith('.svg') and a==10:
            continue
        return 'non-fixture private IPv4 literal (use RFC 5737 TEST-NET examples)'
    return None

RULES=[('private-key material',re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')),
       ('GitHub credential',re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{35,}\b|\bgithub_pat_[A-Za-z0-9_]{65,}\b')),
       ('workstation path',re.compile(rb'/work'+rb'space/scratch/|/Us'+rb'ers/[^/\s]+/|/ro'+rb'ot/(?!\.cache)[A-Za-z0-9_.-]+'))]


def sources(path):
    if path.is_file():
        with zipfile.ZipFile(path) as archive:
            if archive.testzip():raise ValueError('ZIP integrity check failed')
            seen=set();roots=set()
            for member in archive.infolist():
                if member.filename in seen:raise ValueError('Duplicate ZIP member')
                seen.add(member.filename);p=PurePosixPath(member.filename)
                if p.is_absolute() or '..' in p.parts or '\\' in member.filename:raise ValueError('Unsafe ZIP path')
                if stat.S_ISLNK(member.external_attr >> 16):raise ValueError('ZIP contains a symbolic link')
                if not member.is_dir():roots.add(p.parts[0])
            if len(roots)!=1:raise ValueError('Expected one full-application root in ZIP')
            root=next(iter(roots))+'/'
            for member in archive.infolist():
                if not member.is_dir():yield member.filename.removeprefix(root),archive.read(member)
    else:
        if (path/'.git').exists():
            raw=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=path)
            names=sorted(set(n.decode() for n in raw.split(b'\0') if n))
        else:
            names=sorted(str(p.relative_to(path)) for p in path.rglob('*') if p.is_file())
        for name in names:
            p=path/name
            if p.is_symlink():raise ValueError('Source tree contains a symbolic link')
            if p.is_file():yield name,p.read_bytes()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path,help='Git source directory, extracted source directory or full source ZIP')
    parser.add_argument('--deny-file',type=Path,help='Private JSON with literals/patterns to exclude; values are never printed')
    args=parser.parse_args();rules=list(RULES)
    if args.deny_file:
        deny=json.loads(args.deny_file.read_text())
        for value in deny.get('literals',[]):
            if value:rules.append(('supplied private identifier',re.compile(re.escape(value.encode()),re.I)))
        for value in deny.get('patterns',[]):rules.append(('supplied private identifier',re.compile(value.encode(),re.I)))
    failures=[];total=0;binary=0
    try:
        for name,data in sources(args.source.resolve()):
            total+=1;parts=PurePosixPath(name).parts
            if b'\0' in data[:8192]:binary+=1
            if ('.git' in parts or '.secrets' in parts or '__pycache__' in parts
                    or parts[0] in {'target','data','artifacts'} or parts[:2] == ('cli','dist')
                    or parts[-1] in RUNTIME_NAMES
                    or (parts[-1].startswith('.env.') and parts[-1] != '.env.example')):
                failures.append((name,'runtime state or secret filename'))
            if parts[-1].lower().endswith(('.key','.pem','.p12','.pfx','.jks','.crt')):
                failures.append((name,'runtime key/certificate/truststore'))
            private_evidence=private_ipv4_evidence(name,data)
            if private_evidence:failures.append((name,private_evidence))
            for label,pattern in rules:
                if pattern.search(data):failures.append((name,label))
    except (OSError,ValueError,zipfile.BadZipFile,subprocess.CalledProcessError) as error:
        print('FAIL: source package could not be inspected ('+type(error).__name__+')',file=sys.stderr);return 1
    for name,label in failures:print('FAIL: '+name+' ['+label+']',file=sys.stderr)
    if failures:return 1
    print(f'PASS: inspected {total} source files, including {binary} binary assets; no prohibited runtime files or configured private identifiers found.')
    return 0

if __name__=='__main__':sys.exit(main())
