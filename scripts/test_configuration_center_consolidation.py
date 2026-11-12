#!/usr/bin/env python3
"""Real packaged-app regression: legacy identity parity, one editor and live scan jobs.

Uses temporary H2/config/data, synthetic credentials, a wire-level ClamD fixture,
Playwright and Chromium. It never contacts real identity providers or production
volumes. The JDK seeds an encrypted legacy provider row to exercise upgrades.
"""
import argparse
import datetime
import importlib.util
import json
import os
from pathlib import Path
import re
import socketserver
import struct
import subprocess
import tempfile
import threading
import zipfile
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('configuration_smoke',ROOT/'tools/verify-configuration-center.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
LEGACY_SECRET='synthetic-legacy-directory-secret'
LEGACY={'enabled':'false','url':'ldaps://directory.example.invalid:636','baseDn':'dc=example,dc=invalid',
 'managerDn':'cn=service,dc=example,dc=invalid','userSearchBase':'ou=people','userSearchFilter':'(uid={0})',
 'groupSearchBase':'ou=groups','groupSearchFilter':'(uniqueMember={0})','groupMemberAttribute':'uniqueMember',
 'groupNameAttribute':'displayName','startTls':'false','nestedGroups':'true','maxNestedDepth':'4','pooled':'true',
 'referrals':'throw','connectTimeoutMillis':'3000','readTimeoutMillis':'7000','viewer':'readers','operator':'operators','admin':'administrators'}


class Daemon(socketserver.StreamRequestHandler):
    def handle(self):
        first=self.rfile.read(1)
        if not first:return
        end=b'\0' if first==b'z' else b'\n';command=bytearray()
        while len(command)<8192:
            char=self.rfile.read(1)
            if not char or char==end:break
            command.extend(char)
        name=command.decode().split(' ',1)[0]
        version='ClamAV 1.5.4/27920/'+datetime.datetime.now(datetime.timezone.utc).strftime('%a %b %d %H:%M:%S %Y')
        if name=='PING':reply='PONG'
        elif name=='VERSION':reply=version
        elif name in ['COMMANDS','VERSIONCOMMANDS']:reply=version+'| COMMANDS: PING VERSION STATS INSTREAM MULTISCAN SCAN CONTSCAN VERSIONCOMMANDS'
        elif name=='STATS':reply='POOLS: 1\nTHREADS: live 2 idle 2 max 2\nQUEUE: 0 items\nEND'
        elif name=='INSTREAM':
            payload=bytearray()
            while True:
                header=self.rfile.read(4)
                if len(header)!=4:return
                size=struct.unpack('!I',header)[0]
                if not size:break
                remaining=size
                while remaining:
                    chunk=self.rfile.read(remaining)
                    if not chunk:return
                    payload.extend(chunk);remaining-=len(chunk)
            self.server.release.wait(35)
            if b'CLAMD_ERROR' in payload:reply='stream: Synthetic scanner failure ERROR'
            elif b'EICAR' in payload:reply='stream: Win.Test.EICAR_HDB-1 FOUND'
            else:reply='stream: OK'
        else:reply='UNKNOWN COMMAND'
        try:self.wfile.write(reply.encode()+end)
        except (BrokenPipeError,ConnectionResetError):pass


class Scanner(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True


def seed_legacy(jar,work):
    with zipfile.ZipFile(jar) as archive:
        name=next(name for name in archive.namelist() if re.match(r'BOOT-INF/lib/h2-[^/]+\.jar$',name))
        (work/'h2-fixture.jar').write_bytes(archive.read(name))
    (work/'legacy-options.json').write_text(json.dumps(LEGACY))
    (work/'SeedLegacy.java').write_text(r'''
import java.nio.file.*;import java.nio.file.attribute.*;import java.sql.*;import java.util.*;import java.security.*;
import javax.crypto.*;import javax.crypto.spec.*;import java.time.Instant;
class SeedLegacy {
 public static void main(String[] args) throws Exception {
  Path keyFile=Path.of("conf/auth-settings.key");byte[] key;
  if(Files.exists(keyFile)) key=Base64.getDecoder().decode(Files.readString(keyFile).trim());
  else {key=new byte[32];new SecureRandom().nextBytes(key);Files.writeString(keyFile,Base64.getEncoder().encodeToString(key));
   try {Files.setPosixFilePermissions(keyFile,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored) {}}
  byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
  c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
  byte[] encrypted=c.doFinal("synthetic-legacy-directory-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  byte[] joined=new byte[12+encrypted.length];System.arraycopy(iv,0,joined,0,12);System.arraycopy(encrypted,0,joined,12,encrypted.length);
  try(Connection db=DriverManager.getConnection("jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE","sa","");
      PreparedStatement insert=db.prepareStatement("INSERT INTO auth_provider_settings(provider,settings_json,encrypted_secret,updated_at,updated_by) VALUES (?,?,?,?,?)")) {
   insert.setString(1,"ldap");insert.setString(2,Files.readString(Path.of("legacy-options.json")));
   insert.setString(3,"gcm1:"+Base64.getEncoder().encodeToString(joined));insert.setTimestamp(4,java.sql.Timestamp.from(Instant.now()));
   insert.setString(5,"synthetic-upgrade-fixture");insert.executeUpdate();
  }
 }
}
''')
    subprocess.run(['java','-cp',str(work/'h2-fixture.jar'),str(work/'SeedLegacy.java')],cwd=work,check=True,timeout=40)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=ROOT/'target/clamav-web-client-2.1.0.jar')
    parser.add_argument('--chromium',default=os.environ.get('CHROMIUM_PATH'))
    args=parser.parse_args();jar=args.jar.resolve();count=0
    def check(condition,description):
        nonlocal count
        if not condition:raise AssertionError(description)
        count+=1;print(f'PASS {count}: {description}',flush=True)
    with tempfile.TemporaryDirectory(prefix='configuration-consolidation-') as directory,Scanner(('127.0.0.1',0),Daemon) as daemon:
        work=Path(directory);daemon.release=threading.Event()
        threading.Thread(target=daemon.serve_forever,daemon=True).start()
        extra=('--clamav.service.host=127.0.0.1',f'--clamav.service.port={daemon.server_address[1]}',
               '--CLAMAV_UPLOAD_PATH_SCAN_ENABLED=false','--server.servlet.context-path=/clamav')
        app=None
        def start():
            selected=smoke.Application(jar,work,extra,forwarded=False)
            selected.base+='/clamav'
            try:selected.wait('/login')
            except Exception:
                selected.close();raise
            return selected
        try:
            app=start();app.close();seed_legacy(jar,work);app=start()
            with sync_playwright() as runtime:
                browser=runtime.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
                context=browser.new_context(viewport={'width':1400,'height':1000})
                page=context.new_page();errors=[];requests=[];payloads=[];navigations=[];origins=set()
                page.on('pageerror',lambda error:errors.append(str(error)))
                page.on('request',lambda request:(requests.append(request.url),payloads.append(request.post_data or '')))
                page.on('framenavigated',lambda frame:navigations.append(frame.url) if frame==page.main_frame else None)
                def login():
                    origins.add(app.base.removesuffix('/clamav'));page.goto(app.base+'/login')
                    page.locator('form[action$="/login"] input[name="username"]').fill('admin')
                    page.locator('form[action$="/login"] input[name="password"]').fill(smoke.ADMIN_PASSWORD)
                    page.get_by_role('button',name='Sign in locally',exact=True).click();page.wait_for_url('**/dashboard')
                login();page.goto(app.base+'/admin/settings/authentication')
                expect(page.get_by_role('tab',name='Identity & API',exact=True)).to_have_attribute('aria-selected','true')
                check('/admin/configuration-center?category=identity' in page.url,'Legacy identity bookmark reaches the central category under the context path')
                check(page.locator('aside a[href$="/admin/settings"],aside a[href$="/admin/settings/authentication"]').count()==0,'Duplicate settings/authentication navigation is removed')
                page.get_by_role('switch',name='LDAP / Active Directory',exact=True).check()
                labels={'url':'LDAP URL','baseDn':'Base DN','managerDn':'Bind / manager DN','userSearchBase':'User search base',
                    'userSearchFilter':'User search filter','groupSearchBase':'Group search base','groupSearchFilter':'Group search filter',
                    'groupMemberAttribute':'Group member attribute','groupNameAttribute':'Group name attribute','maxNestedDepth':'Maximum nested-group depth',
                    'connectTimeoutMillis':'Connect timeout (ms)','readTimeoutMillis':'Read timeout (ms)','referrals':'LDAP referrals',
                    'pooled':'Pool LDAP connections','nestedGroups':'Search nested groups','startTls':'Use StartTLS',
                    'viewer':'Viewer groups','operator':'Operator groups','admin':'Admin groups'}
                check(all(page.get_by_label(label,exact=True).input_value()==LEGACY[key] for key,label in labels.items()),'Every legacy LDAP option is shown with its existing value, including filters, attributes, nesting, pooling, referrals and limits')
                check(page.get_by_label('Bind password',exact=True).input_value()=='' and 'retain' in page.get_by_label('Bind password',exact=True).get_attribute('placeholder'),'Retained legacy bind password is configured and write-only')
                page.get_by_label('Read timeout (ms)',exact=True).fill('8000')
                page.get_by_role('switch',name='LDAP / Active Directory',exact=True).uncheck()
                page.get_by_role('switch',name='OpenID Connect / SSO',exact=True).check()
                page.get_by_label('Requested scopes',exact=True).fill('openid profile email custom:read')
                check('/clamav/login/oauth2/code/clamav' in page.locator('[data-section="oidc"]').inner_text(),'OIDC callback guidance uses the actual application context')
                page.get_by_role('switch',name='OpenID Connect / SSO',exact=True).uncheck()
                page.get_by_role('tab',name='Monitoring',exact=True).click()
                page.get_by_role('switch',name='Prometheus & Grafana',exact=True).check()
                page.get_by_role('button',name='Generate scrape token',exact=True).click()
                token=page.locator('[data-section="metrics"] .cc-note code').inner_text()
                check(bool(re.fullmatch('[0-9a-f]{64}',token)),'Scrape-token generation is available in the sole editor and creates a draft')
                page.get_by_label('Allow configuration over HTTP on this trusted network',exact=True).check()
                page.get_by_role('button',name='Review changes',exact=False).click()
                expect(page.get_by_role('dialog')).to_be_visible()
                check(token not in page.get_by_role('dialog').inner_text() and LEGACY_SECRET not in page.get_by_role('dialog').inner_text(),'Central review masks new and retained credentials')
                page.get_by_label('Local administrator password',exact=True).fill(smoke.ADMIN_PASSWORD)
                page.get_by_role('button',name='Apply reviewed changes',exact=True).click();expect(page.get_by_role('dialog')).to_have_count(0)
                expect(page.locator('.cc-restart')).to_contain_text('Read timeout')
                response=context.request.get(app.base+'/actuator/prometheus',headers={'Authorization':'Bearer '+token})
                check(response.status==200,'Generated scrape token applies live after reviewed administrator verification')
                csrf=page.locator('meta[name="_csrf"]').get_attribute('content')
                before=context.request.get(app.base+'/admin/configuration-center/api/view').json()['version']
                for path in ['/admin/settings','/admin/configuration/settings','/admin/integrations/prometheus','/admin/settings/authentication/ldap/save','/admin/settings/authentication/oidc/test']:
                    response=context.request.post(app.base+path,form={'secret':'retired-payload'},headers={'X-CSRF-TOKEN':csrf})
                    assert response.status==409 and response.json()['saved'] is False
                after=context.request.get(app.base+'/admin/configuration-center/api/view').json()['version']
                check(before==after,'All retired save/test paths reject changes without altering the central revision')
                for path in ['/admin/configuration','/admin/integrations']:
                    page.goto(app.base+path);assert page.locator('.app-content form,.app-content input,.app-content select').count()==0
                check(True,'Daemon status/files and monitoring/download pages contain no duplicate configuration inputs')
                page.goto(app.base+'/admin/endpoints')
                check(page.locator('.app-content tbody tr').first.locator('form').count()==0,'Default engine has no second editor on the endpoint resource page')
                app.close();app=start();login();page.goto(app.base+'/admin/configuration-center?section=ldap')
                expect(page.get_by_role('tab',name='Identity & API',exact=True)).to_have_attribute('aria-selected','true')
                view=context.request.get(app.base+'/admin/configuration-center/api/view').json()
                fields={field['definition']['key']:field for field in view['fields']}
                check(fields['clamav.identity.ldap.read-timeout-millis']['value']=='8000' and not fields['clamav.identity.ldap.read-timeout-millis']['pendingRestart'],'Changed LDAP limit activates after real process recreation')
                check(all(fields['clamav.identity.ldap.'+suffix]['value']==expected for suffix,expected in [('manager-dn',LEGACY['managerDn']),('group-member-attribute','uniqueMember'),('group-name-attribute','displayName'),('max-nested-depth','4'),('referrals','throw')]) and fields['clamav.identity.ldap.manager-password']['configured'],'Partial central edits preserve the remaining legacy LDAP settings and encrypted credential after restart')
                scopes=fields['clamav.identity.oidc.scopes']
                check(scopes['value']=='openid,profile,email,custom:read' and not scopes['pendingRestart'],'OIDC scopes are canonical and no false restart warning remains')
                page.goto(app.base+'/scan');daemon.release.clear()
                page.locator('form[action$="/scan/upload"] input[type="file"]').set_input_files([
                    {'name':'clean.txt','mimeType':'text/plain','buffer':b'clean payload'},
                    {'name':'detected.txt','mimeType':'text/plain','buffer':b'EICAR fixture'},
                    {'name':'scanner-error.txt','mimeType':'text/plain','buffer':b'CLAMD_ERROR fixture'}])
                page.locator('form[action$="/scan/upload"]').get_by_role('button',name='Queue upload scan',exact=True).click();page.wait_for_url('**/jobs')
                try:
                    expect(page.locator('tr[data-job-status="RUNNING"]')).to_have_count(2,timeout=15000)
                    expect(page.locator('tr[data-job-status="QUEUED"]')).to_have_count(1)
                except Exception:
                    print('Synthetic job table at failure:',page.locator('.app-content').inner_text(),flush=True)
                    print('Application job diagnostics:', '\n'.join(line for line in (work/'process.log').read_text(errors='replace').splitlines() if 'Job ' in line or 'finished ERROR' in line)[-3000:],flush=True)
                    raise
                check(True,'Scan Jobs displays two real running workers and one queued job')
                link=page.locator('tr[data-job-id] a').first;href=link.get_attribute('href');link.focus();navigation_count=len(navigations)
                daemon.release.set();expect(page.locator('tr[data-job-status="FINISHED"]')).to_have_count(3,timeout=20000)
                for name,verdict in [('clean.txt','OK'),('detected.txt','VIRUS_FOUND'),('scanner-error.txt','ERROR')]:
                    row=page.locator('tr[data-job-id]').filter(has_text=name);expect(row).to_contain_text(verdict)
                check(len(navigations)==navigation_count,'All three final statuses and verdicts appear without navigation or reload')
                check(page.evaluate('document.activeElement.getAttribute("href")')==href,'Automatic status refresh preserves keyboard focus on a job link')
                for url in requests:
                    assert any(url.startswith(origin+'/') for origin in origins),url
                check(not any(LEGACY_SECRET in payload for payload in payloads),'Retained legacy secret is never returned to or resubmitted by the browser')
                def fail(route):route.fulfill(status=503,content_type='text/plain',body='Temporary fixture outage')
                page.route('**/jobs/table',fail)
                expect(page.locator('[data-jobs-refresh-status]')).to_contain_text('Retrying automatically',timeout=12000)
                check(page.locator('tr[data-job-status="FINISHED"]').count()==3,'A temporary refresh error keeps the last completed results visible')
                page.unroute('**/jobs/table',fail)
                expect(page.locator('[data-jobs-refresh-status]')).to_contain_text('Updates automatically',timeout=12000)
                check(True,'Automatic refresh recovers after the temporary endpoint failure')
                page.get_by_role('link',name='Configuration center',exact=True).click();page.wait_for_url('**/admin/configuration-center')
                page.wait_for_timeout(500);reads=sum(url.endswith('/jobs/table') for url in requests);page.wait_for_timeout(5500)
                check(sum(url.endswith('/jobs/table') for url in requests)==reads,'Leaving Scan Jobs stops its polling during tab navigation')
                reads=sum(url.endswith('/jobs') for url in requests)
                page.locator('aside a[href$="/jobs"]').click();page.wait_for_url('**/jobs')
                expect(page.locator('tr[data-job-status="FINISHED"]')).to_have_count(3)
                check(sum(url.endswith('/jobs') for url in requests)>reads,'Returning through PJAX fetches a fresh job page rather than cached HTML')
                check(not errors,'No JavaScript exceptions during configuration, restart and job completion workflows')
                browser.close()
        finally:
            daemon.release.set()
            if app:app.close()
            daemon.shutdown()
    print(f'All {count} consolidation and live-job browser checks passed.',flush=True)


if __name__=='__main__':main()
