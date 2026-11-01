#!/usr/bin/env python3
"""Chromium regression for the sole identity editor and its central Test/Review/Apply routes.

Runs production Configuration Center JS/CSS and the shared catalogue. Network
responses are controlled fixtures; real Spring persistence is checked by Maven
and test_configuration_center_consolidation.py. No real credentials or providers.
"""
from pathlib import Path
import copy
import json
import os
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
BASE='https://configuration.test'
CATALOG=json.loads((ROOT/'src/main/resources/configuration-center/catalog.json').read_text())


def main():
    view={'version':'0','categories':CATALOG['categories'],'sections':CATALOG['sections'],
          'pendingRestart':[],'databaseChoiceRequired':False,'fields':[]}
    for definition in CATALOG['fields']:
        value=definition['defaultValue']
        if definition['key']=='clamav.identity.ldap.enabled':value='true'
        view['fields'].append({'definition':definition,'value':'' if definition['secret'] else value,
            'activeValue':value,'locked':False,'configured':False,'source':'Application default','pendingRestart':False})
    fields={field['definition']['key']:field for field in view['fields']}
    sent=[];reviews={};errors=[]
    with sync_playwright() as runtime:
        browser=runtime.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH','/usr/bin/chromium'),args=['--no-sandbox'])
        page=browser.new_page()
        page.on('pageerror',lambda error:errors.append(str(error)))
        def route(request):
            path=request.request.url.split(BASE,1)[-1]
            if path.startswith('/js/'):
                return request.fulfill(content_type='application/javascript',body=(ROOT/'src/main/resources/static/js/configuration-center.js').read_text())
            if path.startswith('/css/'):
                return request.fulfill(content_type='text/css',body=(ROOT/'src/main/resources/static/css/configuration-center.css').read_text())
            if '/api/' not in path:
                return request.fulfill(content_type='text/html',body='''<html><head><meta name="_csrf" content="fixture-csrf"><meta name="_csrf_header" content="X-CSRF-TOKEN"><link rel="stylesheet" href="/css/configuration-center.css"><script defer src="/js/configuration-center.js"></script></head><body><section class="configuration-center" data-mode="admin" data-endpoint="/admin/configuration-center"><p class="cc-message" hidden></p><p class="cc-loading">Loading</p><div class="cc-content" hidden></div></section></body></html>''')
            action=path.rsplit('/',1)[-1]
            body=request.request.post_data_json if request.request.method=='POST' else {}
            sent.append((action,body,request.request.headers))
            status=200
            if action=='view':response=view
            elif action=='test':
                status=400;response={'message':'Provider unavailable; draft retained'}
            elif action=='review':
                identifier='review-'+str(len(reviews));reviews[identifier]=copy.deepcopy(body)
                changes=[]
                for key,value in body['changes'].items():
                    definition=fields[key]['definition']
                    changes.append({'label':definition['label'],'before':'[private]' if definition['secret'] else fields[key]['value'],
                                    'after':'[replaced]' if definition['secret'] else value,'apply':definition['apply']})
                response={'reviewId':identifier,'changes':changes,'sensitive':True,'databaseChange':False}
            elif action=='apply':
                if body['verificationPassword']!='fixture-admin-password':status=400;response={'message':'Administrator verification failed'}
                else:
                    for key,value in reviews[body['reviewId']]['changes'].items():
                        fields[key]['value']='' if fields[key]['definition']['secret'] else value
                        fields[key]['configured']=bool(value)
                    view['version']=str(int(view['version'])+1)
                    response={'message':'Configuration saved','view':view}
            else:raise AssertionError('Unexpected action '+action)
            request.fulfill(status=status,content_type='application/json',body=json.dumps(response))
        page.route(BASE+'/**',route)
        page.goto(BASE+'/admin/configuration-center?section=ldap')
        expect(page.get_by_role('tab',name='Identity & API',exact=True)).to_have_attribute('aria-selected','true')
        for label in ['Group member attribute','Group name attribute','Connect timeout (ms)','Read timeout (ms)','LDAP referrals','Pool LDAP connections','Search nested groups']:
            expect(page.get_by_label(label,exact=True)).to_be_visible()
        page.get_by_label('Search nested groups',exact=True).select_option('true')
        expect(page.get_by_label('Maximum nested-group depth',exact=True)).to_be_visible()
        expect(page.get_by_label('Bind password file',exact=True)).not_to_be_visible()
        print('PASS: complete LDAP options, conditional nesting depth and advanced secret-file controls',flush=True)
        for provider,field,label in [('ldap','managerDn','Bind / manager DN'),('oidc','clientId','Client ID')]:
            if provider=='oidc':page.get_by_role('switch',name=next(section['title'] for section in CATALOG['sections'] if section['id']=='oidc'),exact=True).check()
            page.get_by_label(label,exact=True).fill('retained-draft-'+provider)
            secret_label='Bind password' if provider=='ldap' else 'Client secret'
            secret='fixture-private-secret-'+provider
            page.get_by_label(secret_label,exact=True).fill(secret)
            page.locator('[data-section="'+provider+'"] button').filter(has_text='Test connection').click()
            expect(page.locator('[data-section="'+provider+'"] .cc-test-result')).to_contain_text('draft retained')
            expect(page.get_by_label(label,exact=True)).to_have_value('retained-draft-'+provider)
            assert sent[-1][0]=='test' and sent[-1][1]['section']==provider
            assert all(fields[key]['definition']['section']==provider for key in sent[-1][1]['changes'])
            page.get_by_role('button',name='Review changes',exact=False).click()
            expect(page.get_by_role('dialog')).to_be_visible()
            assert secret not in page.get_by_role('dialog').inner_text()
            page.get_by_label('Local administrator password',exact=True).fill('wrong')
            page.get_by_role('button',name='Apply reviewed changes',exact=True).click()
            expect(page.get_by_role('dialog')).to_contain_text('verification failed')
            page.get_by_label('Local administrator password',exact=True).fill('fixture-admin-password')
            page.get_by_role('button',name='Apply reviewed changes',exact=True).click()
            expect(page.get_by_role('dialog')).to_have_count(0)
            print('PASS '+provider.upper()+': central Test/Review/Apply routes, draft retention, secret masking and verification',flush=True)
        assert all(action in ['view','test','review','apply'] for action,_,_ in sent)
        assert all(headers.get('x-csrf-token')=='fixture-csrf' for action,_,headers in sent if action!='view')
        assert not errors,errors
        print('PASS: no legacy identity posts; central mutations retain CSRF; no JavaScript exceptions',flush=True)
        browser.close()


if __name__=='__main__':main()
