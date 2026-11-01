#!/usr/bin/env python3
"""Audit every real GUI control, then apply non-identity settings and restart the JAR.

Uses isolated conf/data, a synthetic ClamD, a self-signed HTTPS REST receiver and
an imported PKCS12 truststore. No Docker, proxy, public DNS or production services.
"""
import argparse
import collections
import http.server
import json
import os
import re
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import urllib.request
from playwright.sync_api import sync_playwright, expect
from test_configuration_center_consolidation import smoke, Scanner, Daemon

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=ROOT/'target/clamav-web-client-2.1.0.jar')
    parser.add_argument('--chromium',default=os.environ.get('CHROMIUM_PATH'))
    args=parser.parse_args();jar=args.jar.resolve();count=0
    def check(condition,description):
        nonlocal count
        if not condition:raise AssertionError(description)
        count+=1;print(f'PASS {count}: {description}',flush=True)
    with tempfile.TemporaryDirectory(prefix='configuration-all-fields-') as directory,Scanner(('127.0.0.1',0),Daemon) as daemon:
        work=Path(directory);daemon.release=threading.Event();daemon.release.set()
        threading.Thread(target=daemon.serve_forever,daemon=True).start()
        for executable in ('openssl','keytool'):
            if not shutil.which(executable):raise RuntimeError(f'{executable} is required for the isolated truststore fixture')
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(work/'key.pem'),'-out',str(work/'cert.pem'),
            '-days','1','-subj','/CN=localhost','-addext','subjectAltName=IP:127.0.0.1,DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
        trust=work/'receiver.p12';trust_password='SyntheticTruststorePassword456'
        subprocess.run(['keytool','-importcert','-noprompt','-alias','fixture','-file',str(work/'cert.pem'),'-keystore',str(trust),
            '-storetype','PKCS12','-storepass',trust_password],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
        requests=[]
        class Receiver(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def respond(self):
                body=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.headers.get('Content-Length') else b''
                requests.append((self.path,self.headers.get('Authorization'),body))
                data=b'{"jobs":[],"totals":{"files_scanned":7}}'
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            do_GET=respond
            do_POST=respond
        receiver=http.server.ThreadingHTTPServer(('127.0.0.1',0),Receiver)
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(work/'cert.pem',work/'key.pem');receiver.socket=tls.wrap_socket(receiver.socket,server_side=True)
        threading.Thread(target=receiver.serve_forever,daemon=True).start()
        app=smoke.Application(jar,work,forwarded=False)
        try:
            app.wait('/setup/');status,view=app.api('view',{})
            status,review=app.api('review',{'version':view['version'],'changes':{'clamav.service.host':'127.0.0.1','clamav.service.port':str(daemon.server_address[1]),
                'spring.datasource.password':'SyntheticDatabasePassword456'},'acknowledgeHttp':True})
            check(status==200,'Initial database credentials can be reviewed over explicitly confirmed direct HTTP')
            check(app.api('apply',{'reviewId':review['reviewId'],'acknowledgeDatabase':True,'acknowledgeHttp':True})[0]==200,'Initial database configuration saves before startup')
            check(app.api('start',{})[0]==200,'Standalone application starts without a reverse proxy');app.wait('/login');app.login()
            with sync_playwright() as runtime:
                browser=runtime.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
                context=browser.new_context(viewport={'width':1440,'height':1100});page=context.new_page();errors=[]
                page.on('pageerror',lambda error:errors.append(str(error)))
                def login():
                    page.goto(app.base+'/login');form=page.locator('form[action$="/login"]')
                    form.locator('[name="username"]').fill('admin');form.locator('[name="password"]').fill(smoke.ADMIN_PASSWORD)
                    form.locator('button[type="submit"]').click();page.wait_for_url('**/dashboard')
                def center():
                    page.goto(app.base+'/admin/configuration-center');expect(page.get_by_role('tab',name='Connections',exact=True)).to_be_visible()
                def section(definition):
                    page.get_by_role('tab',name=definition['category'],exact=True).click()
                    if definition['enableKey']:
                        page.get_by_role('switch',name=definition['title'],exact=True).check()
                    card=page.locator('[data-section="'+definition['id']+'"]');expand=card.locator('.cc-expand')
                    if expand.get_attribute('aria-expanded')!='true':expand.click()
                    details=card.locator('details.cc-advanced')
                    if details.count() and not details.evaluate('(element)=>element.open'):details.locator('summary').click()
                    return card
                login();center();status,view=app.admin('view');definitions={f['definition']['key']:f['definition'] for f in view['fields']}
                check(status==200 and len(definitions)==107,'The live server exposes exactly 107 unique editable settings')
                visited=[]
                for definition in view['sections']:
                    fields=[f for f in view['fields'] if f['definition']['section']==definition['id']]
                    if not fields:continue
                    card=section(definition)
                    if definition['id']=='database':
                        card.get_by_role('combobox',name='Database type',exact=True).select_option('postgresql');card=section(definition)
                    if definition['id']=='ldap':
                        card.locator('[data-key="clamav.identity.ldap.nested-groups"] select').select_option('true');card=section(definition)
                    for field in fields:
                        key=field['definition']['key']
                        if key==definition['enableKey'] and definition['id']!='proxy':
                            check(page.get_by_role('switch',name=definition['title'],exact=True).count()==1,'Single feature control: '+key)
                        else:
                            container=card.locator('[data-key="'+key+'"]');control=container.locator('select,input:not([type="checkbox"])')
                            check(container.count()==1 and control.count()==1 and control.is_visible() and control.is_enabled(),'Single connected GUI field: '+key)
                            control_id=control.get_attribute('id');check(card.locator('label[for="'+control_id+'"]').count()==1,'Accessible field label: '+key)
                            if field['definition']['secret']:check(control.input_value()=='','Credential is write-only: '+key)
                        visited.append(key)
                check(len(visited)==107 and len(set(visited))==107,'Every field is reachable once across all seven categories, including conditional and advanced fields')
                center() # Discard the inspection draft; no test configuration was saved.
                for old in ('/admin/settings','/admin/settings/authentication'):
                    page.goto(app.base+old);expect(page).to_have_url(re.compile('/admin/configuration-center'))
                for old in ('/admin/configuration','/admin/integrations'):
                    page.goto(app.base+old);check(page.locator('.app-content form,.app-content input,.app-content select').count()==0,'Former page has no global configuration editor: '+old)
                center();uploads=work/'audit-uploads';quarantine=work/'audit-quarantine';scans=work/'audit-scans';scans.mkdir()
                clamd=work/'clamd.conf';clamd.write_text('TCPSocket 3310\nStreamMaxLength 4096\n')
                fresh=work/'freshclam.conf';fresh.write_text('Checks 12\nDatabaseMirror database.clamav.net\n')
                token='SyntheticScrapeTokenForAllFieldsAudit0123456789';rest_secret='SyntheticRestApiSecret456';management_port=smoke.free_port()
                changes={'center.clamd.name':'Audited primary','app.allowedScanRoots':str(scans),'app.upload.maxBytes':'32','app.concurrentScans':'3',
                    'app.storage.uploadDir':str(uploads),'app.storage.quarantineDir':str(quarantine),'app.upload.pathScan.enabled':'false',
                    'app.upload.pathScan.fallbackToInstream':'true','app.quarantine.enabled':'true','app.watch.pollSeconds':'45',
                    'spring.servlet.multipart.max-file-size':'1MB','spring.servlet.multipart.max-request-size':'2MB',
                    'clamav.rest.enabled':'true','CLAMAV_REST_URL':f'https://127.0.0.1:{receiver.server_port}','CLAMAV_REST_API_KEY':rest_secret,
                    'CLAMAV_REST_AUTH_MODE':'bearer','CLAMAV_REST_FORM_KEY':'DOCUMENTS','CLAMAV_REST_WEBHOOK_FIELD':'callbackId',
                    'CLAMAV_REST_TIMEOUT_SECONDS':'2','CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS':'3','CLAMAV_REST_REPORT_MAX_JOBS':'7',
                    'api.v1.scan-rate-limit-per-minute':'5','api.v1.max-page-size':'2','api.v1.clamd.max-database-age-hours':'1',
                    'api.v1.clamd.database-timezone':'Europe/Bucharest','app.webhook.url':'https://hooks.example.test/events',
                    'clamav.webhook.connect-timeout':'1200ms','clamav.webhook.read-timeout':'1300ms','clamav.webhook.dns-timeout':'1400ms',
                    'clamav.webhook.delivery-timeout':'1800ms','clamav.webhook.workers':'3','clamav.webhook.queue-capacity':'7',
                    'server.forward-headers-strategy':'none','server.servlet.context-path':'/audit','server.servlet.session.cookie.same-site':'strict',
                    'javax.net.ssl.trustStore':str(trust),'javax.net.ssl.trustStorePassword':trust_password,'javax.net.ssl.trustStoreType':'PKCS12',
                    'clamav.metrics.enabled':'true','clamav.metrics.scrape-token':token,'clamav.metrics.clamd-poll-ms':'1200',
                    'clamav.metrics.clamd-max-endpoints':'1','management.server.port':str(management_port),
                    'clamav.config.clamd-path':str(clamd),'clamav.config.freshclam-path':str(fresh),'clamav.config.discovery-enabled':'false',
                    'clamav.config.poll-ms':'7000','clamav.ui.default-theme':'dark','clamav.ui.default-language':'fr'}
                for operation in ('STATS','JOBS','HISTORY','WEBHOOKS','SCAN','SCAN_HISTORY'):changes['CLAMAV_REST_'+operation+'_PATH']='/audited/'+operation.lower()
                by_section=collections.defaultdict(dict)
                for key,value in changes.items():by_section[definitions[key]['section']][key]=value
                for definition in view['sections']:
                    if definition['id'] not in by_section:continue
                    original=next((f['value'] for f in view['fields'] if f['definition']['key']==definition['enableKey']),None)
                    card=section(definition)
                    for key,value in by_section[definition['id']].items():
                        if key==definition['enableKey'] and definition['id']!='proxy':continue
                        control=card.locator('[data-key="'+key+'"] select,[data-key="'+key+'"] input:not([type="checkbox"])')
                        if control.evaluate('(element)=>element.tagName')=='SELECT':control.select_option(value)
                        else:control.fill(value)
                    if definition['enableKey'] and definition['id']!='proxy':
                        desired=changes.get(definition['enableKey'],original)
                        page.get_by_role('switch',name=definition['title'],exact=True).set_checked(desired==definition['enabledValue'])
                page.get_by_label('Allow configuration over HTTP on this trusted network',exact=True).check()
                page.get_by_role('button',name='Review changes',exact=False).click();expect(page.get_by_role('dialog')).to_be_visible()
                check(all(secret not in page.get_by_role('dialog').inner_text() for secret in (token,rest_secret,trust_password)),'Multi-category review masks every submitted credential')
                page.get_by_label('Local administrator password',exact=True).fill(smoke.ADMIN_PASSWORD)
                page.get_by_role('button',name='Apply reviewed changes',exact=True).click();expect(page.get_by_role('dialog')).to_have_count(0)
                status,saved=app.admin('view');fields={f['definition']['key']:f for f in saved['fields']}
                check(status==200 and all(fields[k]['value']==v for k,v in changes.items() if not definitions[k]['secret']),'Every edited non-secret field saves through the real GUI review/apply flow')
                check(all(fields[k]['configured'] for k in changes if definitions[k]['secret']),'All submitted credentials are configured without being returned to the browser')
                check(fields['app.upload.maxBytes']['activeValue']=='32' and fields['app.concurrentScans']['activeValue']=='2' and fields['app.concurrentScans']['pendingRestart'],'Live scan policy activates immediately while the running worker count remains unchanged')
                check(app.request('/actuator/prometheus',headers={'Authorization':'Bearer '+token})[0]==200,'The central Prometheus token gates real metrics immediately')
                stored=(work/'conf/configuration-center.properties').read_text();check(all(s not in stored for s in (token,rest_secret,trust_password)),'All credentials remain encrypted in the persistent configuration file')
                app.close();app=smoke.Application(jar,work,forwarded=False);app.wait('/audit/login');app.base+='/audit';app.login();login();center()
                status,restarted=app.admin('view');active={f['definition']['key']:f for f in restarted['fields']}
                check(status==200 and not restarted['pendingRestart'],'All staged fields activate on recreation with no false restart warnings')
                check(all(active[k]['activeValue']==v for k,v in changes.items() if not definitions[k]['secret']),'Every saved non-secret field is active after restart')
                page.goto(app.base+'/dashboard');page.evaluate("localStorage.removeItem('theme')");page.reload()
                expect(page.locator('html')).to_have_attribute('data-theme','dark')
                check(page.locator('html').get_attribute('lang')=='fr' and page.locator('html').get_attribute('data-default-theme')=='dark','Saved default language and theme affect a browser without a personal override')
                page.evaluate("localStorage.setItem('theme','light')");page.reload();expect(page.locator('html')).to_have_attribute('data-theme','light')
                check(True,'A personal theme choice remains authoritative over the application default')
                request=urllib.request.Request(f'http://127.0.0.1:{management_port}/actuator/prometheus',headers={'Authorization':'Bearer '+token})
                with urllib.request.urlopen(request,timeout=10) as response:check(response.status==200,'The saved separate management port serves token-protected metrics')
                check(app.request('/api/v1/jobs?size=3')[0]==400 and app.request('/api/v1/jobs?size=2')[0]==200,'Saved API page size is enforced by real HTTP requests')
                # The untrusted certificate is accepted only because the GUI truststore settings reached JSSE before the REST client was created.
                status,body,_=app.request('/rest-monitor/summary');check(status==200 and requests and requests[-1][0]=='/audited/stats' and requests[-1][1]=='Bearer '+rest_secret,'Saved REST URL, custom path, Bearer key and PKCS12 truststore work against a real self-signed HTTPS receiver')
                page.goto(app.base+'/scan');page.locator('input[name="files"]').set_input_files({'name':'detected.txt','mimeType':'text/plain','buffer':b'EICAR'})
                page.locator('form[action$="/scan/upload"] button').click();page.wait_for_url('**/jobs')
                expect(page.locator('[data-jobs-table]')).to_contain_text('VIRUS_FOUND',timeout=15000)
                check(uploads.is_dir() and quarantine.is_dir() and any(quarantine.iterdir()),'Saved INSTREAM mode, upload storage and quarantine settings produce an actual detected scan and moved file')
                def apply_upload_policy(path_enabled,fallback):
                    center();card=section(next(d for d in view['sections'] if d['id']=='scanning'))
                    card.locator('[data-key="app.upload.pathScan.enabled"] select').select_option(str(path_enabled).lower())
                    card.locator('[data-key="app.upload.pathScan.fallbackToInstream"] select').select_option(str(fallback).lower())
                    page.get_by_role('button',name='Review changes',exact=False).click();expect(page.get_by_role('dialog')).to_be_visible()
                    page.get_by_role('button',name='Apply reviewed changes',exact=True).click();expect(page.get_by_role('dialog')).to_have_count(0)
                def upload(name,data,verdict):
                    page.goto(app.base+'/scan');page.locator('input[name="files"]').set_input_files({'name':name,'mimeType':'text/plain','buffer':data})
                    page.locator('form[action$="/scan/upload"] button').click();page.wait_for_url('**/jobs')
                    expect(page.locator('[data-jobs-table] tr').filter(has_text=name)).to_contain_text(verdict,timeout=15000)
                apply_upload_policy(True,False);upload('native-path-fails.txt',b'clean','ERROR')
                check(True,'Enabling path scans without fallback reports the fixture daemon path failure')
                apply_upload_policy(True,True);upload('path-fallback-detected.txt',b'EICAR','VIRUS_FOUND')
                check(len(list(quarantine.iterdir()))==2,'Enabling INSTREAM fallback live recovers the same path failure and quarantines the detected upload')
                check(not errors,'All-field inspection, save, recreation and scan flows have no browser JavaScript errors')
                context.close();browser.close()
        except Exception:
            if (work/'process.log').exists():
                log=(work/'process.log').read_text(errors='replace')
                print('\n'.join(line for line in log.splitlines() if 'ERROR' in line or 'Caused by:' in line)[-2500:],flush=True)
            raise
        finally:
            app.close();receiver.shutdown();receiver.server_close();daemon.shutdown()
    print(f'Completed {count} real-browser all-field checks.',flush=True)

if __name__=='__main__':main()
