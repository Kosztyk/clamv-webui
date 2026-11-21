#!/usr/bin/env python3
"""Source contract for #39 (Maven/Spring and Docker integration tests must run separately)."""
from pathlib import Path
import json,re
r=Path(__file__).resolve().parents[1]
java=r/'src/main/java/info/trizub/clamav/webclient'
tests=r/'src/test/java/info/trizub/clamav/webclient'
read=lambda p: p.read_text()
checks=[]
def check(name,ok):
    checks.append((name,bool(ok)))
    print(('PASS' if ok else 'FAIL')+': '+name)

catalog=json.loads(read(r/'src/main/resources/configuration-center/catalog.json'))
fields={x['key']:x for x in catalog['fields']}
metrics=read(java/'service/ClamdMetricsView.java')
legacy=read(java/'api/ApiController.java')
api=read(java/'api/v1/ApiV1Controller.java')
web=read(java/'web/WebUiController.java')
security=read(java/'config/SecurityConfig.java')
ws=read(java/'security/SessionWebSocketGuard.java')
service=read(java/'service/NotificationService.java')
transport=read(java/'service/WebhookDeliveryClient.java')
signer=read(java/'service/WebhookSignature.java')
settings=read(java/'service/SettingsService.java')
center=read(java/'configuration/ConfigurationCatalog.java')
engine=read(java/'configuration/ConfigurationEngine.java')
js=read(r/'src/main/resources/static/js/clamdtop.js')
check('same allowlisted projection in both ClamD metrics routes', 'ClamdMetricsView.from(' in legacy and 'ClamdMetricsView.from(' in api)
check('projection excludes full raw STATS, commands, host and errors', not any(x in metrics for x in ['result.put("stats"','result.put("commands"','result.put("endpoint"','result.put("error"','result.put("raw"']) and 'safeCounts' in metrics)
check('VIEWER receives safe legacy projection while privileged users retain raw', 'if (isOperatorOrAdmin(auth)) return snapshot' in legacy)
check('legacy client errors are generic and server errors logged', 'ClamD health information is unavailable.' in legacy and 'log.warn("ClamD STATS probe failed' in read(java/'service/ClamdTopService.java'))
check('viewer main renders numeric-only STATS', 'ClamdMetricsView.summary(view)' in web and 'model.addAttribute("error", "ClamD health information is unavailable.' in web)
check('viewer ClamdTop page contains no native terminal', 'pages/clamdtop-viewer' in web and 'native-clamdtop-terminal' not in read(r/'src/main/resources/templates/pages/clamdtop-viewer.html'))
check('client-side ClamdTop poller reads viewer numeric format', 'data.threads || parsed.threads' in js and 'data.queue || parsed.queue' in js)
check('native raw WebSocket denied for VIEWER by filter and handshake',
      '.requestMatchers("/ws/clamdtop").hasAnyRole("OPERATOR","ADMIN")' in re.sub(r'\s+', '', security)
      and 'isUserInRole("OPERATOR")' in ws and 'isUserInRole("ADMIN")' in ws)
check('browser VIEWER endpoint names sanitized including v1 health',
      '"Endpoint " + ep.getId()' in legacy and 'if (!canSeeEndpointName(auth))' in api
      and re.search(r'new ClamdHealth\(\s*h\.endpointId\(\),\s*"Endpoint " \+ h\.endpointId\(\)', api))
check('dashboard does not render other users latest job rows', 'privileged(auth) ? latest : java.util.List.<ScanJob>of()' in web)
check('webhook signing uses HMAC-SHA256 over raw UTF-8 request body', 'Mac.getInstance("HmacSHA256")' in signer and 'body.getBytes(StandardCharsets.UTF_8)' in signer)
check('signed POST sets mandatory X-Signature header', 'post(URI uri, String body, String signature)' in transport and 'request.setHeader("X-Signature", signature)' in transport and 'A valid X-Signature is required' in transport)
check('webhook disabled without adequate secret', 'WebhookSignature.validSecret(secret)' in service and 'if (!WebhookSignature.validSecret(secret))' in service)
check('webhook body uses structured foundViruses node', 'payload.put("foundViruses", findings)' in service and 'mapper.readTree(raw)' in service)
check('default webhook omits paths and errors', 'new ObjectMapper(), false, false, false' in service and 'if (includePaths)' in service and 'if (includeErrors)' in service)
check('default foundViruses keys are anonymized', 'sanitized.set("item-"' in service)
check('webhook payload is signed before async dispatch', 'String signature = WebhookSignature.sign(secret, body)' in service and 'executor.execute(() -> deliver(destination, body, signature))' in service)
check('Discord disable mentions and obeys privacy', 'allowed_mentions' in service and 'buildDiscordContent(job, findings, includePaths, includeErrors, includeIdentity)' in service)
fieldmap={'app.webhook.signing-secret':('CLAMAV_WEBHOOK_SIGNING_SECRET','text','',True),'app.webhook.include-paths':('CLAMAV_WEBHOOK_INCLUDE_PATHS','boolean','false',False),'app.webhook.include-error-details':('CLAMAV_WEBHOOK_INCLUDE_ERROR_DETAILS','boolean','false',False),'app.webhook.include-identity':('CLAMAV_WEBHOOK_INCLUDE_IDENTITY','boolean','false',False)}
for name,(env,ftype,default,secret) in fieldmap.items():
    f=fields.get(name,{})
    check('GUI field '+name, f.get('section')=='webhook' and f.get('environment')==[env] and f.get('type')==ftype and f.get('defaultValue')==default and f.get('secret')==secret and f.get('apply')=='live')
    check('live SettingsService consumer '+name, ('resolved("'+name+'"') in settings)
check('GUI requires key before enabling webhook', 'WebhookSignature.validSecret(' in center and 'Webhook notifications require a signing secret' in center)
check('GUI clears URL/key and disables notification switch', 'clear.contains("app.webhook.url") || clear.contains("app.webhook.signing-secret")' in engine)
check('all Configuration Center keys unique', len(fields)==len(catalog['fields']))
for name in ['Issue39MetricsProjectionTest','Issue39WebhookPrivacySignatureTest']:
    check(name+' exists', any((tests/'service').glob(name+'.java')))
check('HTTP integration test covers both roles', (tests/'web/Issue39ViewerHttpPrivacyIntegrationTest.java').exists())
check('Configuration Center encryption/override regression test', (tests/'configuration/Issue39WebhookConfigurationCenterTest.java').exists())
check('signature acceptance test uses real local HTTP receiver', 'HttpServer.create(' in read(tests/'service/Issue39WebhookPrivacySignatureTest.java') and 'MessageDigest.isEqual' in read(tests/'service/Issue39WebhookPrivacySignatureTest.java'))
check('migration/privacy documentation included', (r/'docs/ISSUE39_VIEWER_WEBHOOK_SECURITY.md').exists() and 'previously enabled webhooks' in read(r/'docs/ISSUE39_VIEWER_WEBHOOK_SECURITY.md').lower())
failed=[name for name,ok in checks if not ok]
print(f'{len(checks)-len(failed)}/{len(checks)} issue #39 offline source checks passed')
if failed: raise SystemExit(1)
