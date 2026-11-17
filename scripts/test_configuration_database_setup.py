#!/usr/bin/env python3
"""Real HTTP/browser regression for database testing before and after first startup.

Uses temporary application volumes, H2 and an authenticated PostgreSQL protocol
fixture with a deliberately slow login. The fixture is not a PostgreSQL engine.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socketserver
import struct
import tempfile
import threading
import time
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('smoke',ROOT/'tools/verify-configuration-center.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
PASSWORD='synthetic-database-setup-password'

class PostgreSQL(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(10)
        def read(size):
            data=self.rfile.read(size)
            if len(data)!=size:raise EOFError()
            return data
        def text(*values):return ('\0'.join(values)+'\0').encode()
        def send(kind,data):self.wfile.write(kind+struct.pack('!I',len(data)+4)+data);self.wfile.flush()
        def error(state):send(b'E',text('SFATAL','VFATAL','C'+state,'M'+PASSWORD,''))
        try:
            startup=read(struct.unpack('!I',read(4))[0]-4)
            if startup==struct.pack('!I',80877103):
                self.wfile.write(b'N');self.wfile.flush();read(struct.unpack('!I',read(4))[0]-4)
            time.sleep(self.server.delay)
            if self.server.error_state:error(self.server.error_state);return
            send(b'R',struct.pack('!I',3));assert read(1)==b'p';password=read(struct.unpack('!I',read(4))[0]-4).rstrip(b'\0').decode()
            if password!=PASSWORD:error('28P01');return
            send(b'R',struct.pack('!I',0))
            for key,value in [('server_version','16.0'),('client_encoding','UTF8'),('DateStyle','ISO, MDY'),('integer_datetimes','on'),('standard_conforming_strings','on')]:send(b'S',text(key,value))
            send(b'K',struct.pack('!II',1,2));send(b'Z',b'I')
            while True:
                kind=read(1);data=read(struct.unpack('!I',read(4))[0]-4)
                if kind==b'X':return
                if kind!=b'Q':raise AssertionError('Expected simple-query mode')
                query=data.rstrip(b'\0').decode();self.server.queries.append(query)
                if query.upper().startswith('SELECT'):
                    send(b'T',struct.pack('!H',1)+text('?column?')+struct.pack('!IhIhih',0,0,23,4,-1,0))
                    send(b'D',struct.pack('!Hi',1,1)+b'1');send(b'C',text('SELECT 1'))
                else:send(b'C',text('SET'))
                send(b'Z',b'I')
        except (EOFError,ConnectionError,OSError):pass
        except Exception as error:self.server.failures.append(type(error).__name__)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=ROOT/'target/clamav-web-client-2.1.0.jar')
    parser.add_argument('--chromium',default=os.environ.get('CHROMIUM_PATH'))
    args=parser.parse_args();count=0
    def check(value,description):
        nonlocal count
        if not value:raise AssertionError(description)
        count+=1;print(f'PASS {count}: {description}',flush=True)
    with tempfile.TemporaryDirectory(prefix='database-setup-regression-') as folder,Server(('127.0.0.1',0),PostgreSQL) as postgres:
        work=Path(folder);postgres.delay=0;postgres.error_state=None;postgres.queries=[];postgres.failures=[]
        threading.Thread(target=postgres.serve_forever,daemon=True).start()
        app=smoke.Application(args.jar.resolve(),work,forwarded=False)
        try:
            app.wait('/setup/')
            with sync_playwright() as runtime:
                browser=runtime.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
                context=browser.new_context(viewport={'width':1440,'height':1000});page=context.new_page();errors=[];requests=[]
                page.on('pageerror',lambda error:errors.append(str(error)))
                page.on('request',lambda request:requests.append(request.url))
                def unlock():
                    page.goto(app.base+'/setup/');page.get_by_label('Recovery credential',exact=True).fill((work/'conf/configuration-recovery-token').read_text().strip())
                    page.get_by_role('button',name='Unlock configuration',exact=True).click();expect(page.get_by_role('tab',name='Connections',exact=True)).to_be_visible()
                def database():
                    card=page.locator('[data-section="database"]')
                    if card.locator('.cc-expand').get_attribute('aria-expanded')!='true':card.locator('.cc-expand').click()
                    return card
                def test(card):card.get_by_role('button',name='Test connection',exact=True).click()
                unlock();card=database();test(card)
                expect(card.locator('.cc-test-result')).to_have_attribute('data-test-status','not_initialized')
                check('not verified' in card.locator('.cc-test-result').inner_text() and 'Start application' in card.locator('.cc-test-result').inner_text(),'Fresh H2 gives an explicit not-initialized result without claiming a verified connection')
                check('cc-error' not in card.locator('.cc-test-result').get_attribute('class') and 'cc-test-deferred' in card.locator('.cc-test-result').get_attribute('class'),'Uninitialized H2 uses an informational warning rather than an error')
                check(not (work/'data/clamav-web-client.mv.db').exists(),'Testing fresh H2 creates no database file')
                card.get_by_role('combobox',name='Database type',exact=True).select_option('postgresql');card=database()
                url=f'jdbc:postgresql://127.0.0.1:{postgres.server_address[1]}/probe?sslmode=disable&preferQueryMode=simple'
                card.locator('[data-key="spring.datasource.url"] input').fill(url)
                card.locator('[data-key="spring.datasource.username"] input').fill('probe_user')
                card.locator('[data-key="spring.datasource.password"] input[type="password"]').fill(PASSWORD)
                before=len([request for request in requests if request.endswith('/api/test')]);test(card)
                expect(card.locator('.cc-test-result')).to_contain_text('Allow configuration over HTTP')
                check(len([request for request in requests if request.endswith('/api/test')])==before,'HTTP testing still requires explicit confirmation before sending a new database password')
                page.get_by_label('Allow configuration over HTTP on this trusted network',exact=True).check()
                postgres.delay=4;test(card);expect(card.locator('.cc-test-result')).to_have_attribute('data-test-status','succeeded',timeout=12000)
                check('SELECT 1 succeeded' in card.locator('.cc-test-result').inner_text(),'PostgreSQL login slower than three seconds succeeds over direct HTTP before Spring startup')
                check('SELECT 1' in postgres.queries and not any('CREATE ' in query.upper() for query in postgres.queries),'The actual PostgreSQL driver authenticates and validates SELECT 1 without schema creation')
                postgres.delay=0;card.locator('[data-key="spring.datasource.password"] input[type="password"]').fill('synthetic-invalid-password');test(card)
                expect(card.locator('.cc-test-result.cc-error')).to_contain_text('authentication failed')
                check(PASSWORD not in card.locator('.cc-test-result').inner_text(),'Rejected PostgreSQL credentials produce an authentication-specific, secret-safe error')
                card.locator('[data-key="spring.datasource.password"] input[type="password"]').fill(PASSWORD);postgres.error_state='3D000';test(card)
                expect(card.locator('.cc-test-result.cc-error')).to_contain_text('does not exist');check(True,'A missing PostgreSQL database is distinguished from authentication and transport failures')
                postgres.error_state=None;test(card);expect(card.locator('.cc-test-result')).to_have_attribute('data-test-status','succeeded')
                check('cc-error' not in card.locator('.cc-test-result').get_attribute('class'),'A corrected draft can be retested successfully without saving or reloading')
                status,view=app.api('view',{});check(status==200 and view['version']=='0' and not (work/'conf/configuration-center.properties').exists(),'Connection tests never persist drafts or increment the configuration revision')
                check(not postgres.failures,'The PostgreSQL fixture saw valid authentication and SQL protocol exchanges')
                card.get_by_role('combobox',name='Database type',exact=True).select_option('h2');card=database()
                card.locator('[data-key="spring.datasource.password"] input[type="password"]').fill(PASSWORD);test(card)
                expect(card.locator('.cc-test-result')).to_have_attribute('data-test-status','not_initialized')
                page.get_by_role('button',name='Review changes',exact=False).click();dialog=page.get_by_role('dialog');expect(dialog).to_be_visible();dialog.locator('input[type="checkbox"]').check()
                check(PASSWORD not in dialog.inner_text(),'Reviewed H2 initialization masks the new database password')
                page.get_by_role('button',name='Apply reviewed changes',exact=True).click();expect(page.get_by_role('dialog')).to_have_count(0)
                check(not (work/'data/clamav-web-client.mv.db').exists(),'Saving the reviewed H2 configuration still creates no database file')
                page.get_by_role('button',name='Start application',exact=True).click();app.wait('/login')
                check((work/'data/clamav-web-client.mv.db').exists(),'Start application creates and migrates the approved H2 database')
                unlock();card=database();test(card);expect(card.locator('.cc-test-result')).to_have_attribute('data-test-status','succeeded')
                check('SELECT 1 succeeded' in card.locator('.cc-test-result').inner_text(),'The same H2 database verifies successfully after startup using its saved credential')
                _,view=app.api('view',{});version=view['version']
                card.locator('[data-key="spring.datasource.password"] input[type="password"]').fill('synthetic-invalid-password')
                page.get_by_label('Allow configuration over HTTP on this trusted network',exact=True).check();test(card)
                expect(card.locator('.cc-test-result.cc-error')).to_contain_text('authentication failed')
                _,after=app.api('view',{});check(after['version']==version,'Failed tests against the initialized H2 database do not replace saved credentials')
                check(PASSWORD not in (work/'conf/configuration-center.properties').read_text(),'The approved database password remains encrypted')
                check(not errors,'The complete HTTP database-test, review/start and retest flow has no JavaScript errors')
                context.close();browser.close()
        finally:app.close();postgres.shutdown()
    print(f'Completed {count} database setup browser checks.',flush=True)

if __name__=='__main__':main()
