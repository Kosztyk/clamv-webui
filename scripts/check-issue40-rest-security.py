#!/usr/bin/env python3
"""Offline source-contract checks for #40; never substitutes for Maven or Docker."""
from pathlib import Path
import json, re, sys
root=Path(__file__).resolve().parents[1]
base=root/'src/main/java/info/trizub/clamav/webclient'
tests=root/'src/test/java/info/trizub/clamav/webclient'
file=lambda r:(base/r).read_text(encoding='utf-8')
policy=file('service/RestTransportPolicy.java')
bridge=file('service/RestApiBridge.java')
config=file('config/RestApiConfig.java')
client=file('service/RestApiClient.java')
controller=file('web/RestMonitorController.java')
engine=file('configuration/ConfigurationCatalog.java')
tester=re.sub(r'\s+', '', file('configuration/ConfigurationConnectionTester.java'))
catalog=json.loads((root/'src/main/resources/configuration-center/catalog.json').read_text())
fields={v['key']:v for v in catalog['fields']}
checks={
'policy explicitly denies credentialed nonloopback HTTP by default':'!allowInsecure' in policy and 'Credentials over non-loopback HTTP are blocked' in policy,
'policy permits HTTPS and credentialless HTTP':'scheme.equalsIgnoreCase("http")' in policy and 'apiKey.isBlank()' in policy,
'policy exact localhost allowlist':'normalized.equals("localhost")' in policy and '127' in policy and '::1' in policy,
'policy does not DNS resolve bypass':'InetAddress' not in policy and '.getHost()' in policy,
'policy logs warning for deliberate opt-in':'SECURITY WARNING' in policy,
'policy sanitizes URL errors and does not reveal key':'rawUrl' not in policy.split('throw new IllegalArgumentException(')[-1] and 'CLAMAV_REST_ALLOW_INSECURE=true' in policy,
'bridge default binding fail closed':'@Value("${CLAMAV_REST_ALLOW_INSECURE:false}")' in bridge and 'RestTransportPolicy.validate(normalizedBaseUrl, this.apiKey, allowInsecure)' in bridge,
'bridge existing direct constructor fail closed':'this(mapper,baseUrl,apiKey,false,authMode' in re.sub(r'\s+', '', bridge),
'other REST config uses same policy':'RestTransportPolicy.validate(normalized, apiKey, allowInsecure)' in config and '@Value("${CLAMAV_REST_ALLOW_INSECURE:false}")' in config,
'other REST config reads upper-case UI values':'@Value("${CLAMAV_REST_API_KEY:}")' in config,
'config compatibility constructor fail closed':'this(url, apiKey, formKey, timeout, uploadTimeout, reportMaxJobs, false)' in config,
'GUI security field exists':fields.get('CLAMAV_REST_ALLOW_INSECURE',{}).get('section')=='rest',
'GUI default false and restart':fields.get('CLAMAV_REST_ALLOW_INSECURE',{}).get('defaultValue')=='false' and fields['CLAMAV_REST_ALLOW_INSECURE']['apply']=='restart',
'GUI environment override supported':fields['CLAMAV_REST_ALLOW_INSECURE'].get('environment')==['CLAMAV_REST_ALLOW_INSECURE'],
'GUI field security sensitive':fields['CLAMAV_REST_ALLOW_INSECURE'].get('sensitive') is True,
'GUI combo validates key+URL':'RestTransportPolicy.validate(values.getOrDefault("CLAMAV_REST_URL"' in re.sub(r'\s+', '', engine) and 'CLAMAV_REST_ALLOW_INSECURE' in engine,
'GUI connection tester validates before network':bool(re.search(
    r'RestTransportPolicy\.validate\(base,secret,.*?HttpURLConnectionconnection=\(HttpURLConnection\)URI\.create\(base\+path\)', tester)),
'no redirects in bridge':'HttpClient.Redirect.NEVER' in bridge,
'no redirects in secondary client':'HttpClient.Redirect.NEVER' in client,
'bridge bounded stream body':'BodyHandlers.ofInputStream()' in bridge and 'MAX_RESPONSE_BYTES + 1' in bridge,
'secondary client bounded stream body':'BodyHandlers.ofInputStream()' in client and 'readNBytes(MAX_RESPONSE_BYTES + 1)' in client,
'secondary client rejects declared excess':'firstValueAsLong("Content-Length")' in client,
'controller shields upstream bodies':all(
    marker in re.sub(r'\s+', '', controller) for marker in ('response.status()>=400','safe.put("error"','safe.put("message"')),
'controller strips upstream exception details':'boolean upstream = error.getStatusCode().is5xxServerError()' in controller,
'failfast and response cap JUnit tests present':(tests/'service/Issue40RestSecurityTest.java').is_file(),
'GUI regression JUnit tests present':(tests/'configuration/Issue40RestConfigurationCenterTest.java').is_file(),
'MockMvc upstream error test present':'upstreamErrorBodiesAreNotRelayedEvenIfBridgeReturnedThem' in (tests/'web/RestMonitorIntegrationTest.java').read_text(),
'new REST docs included':(root/'docs/ISSUE40_REST_TRANSPORT_SECURITY.md').is_file(),
'archive change log included':(root/'ISSUE40_CHANGED_FILES.md').is_file(),
'catalog keys unique':len(fields)==len(catalog['fields']),
'compose does not set insecure override to true by default':'      CLAMAV_REST_ALLOW_INSECURE: "true"' not in (root/'docker-compose.yml').read_text(),
}
for name,ok in checks.items():print(('PASS' if ok else 'FAIL')+': '+name)
print(f'Issue #40 offline source contracts: {sum(checks.values())}/{len(checks)} passed')
sys.exit(0 if all(checks.values()) else 1)
