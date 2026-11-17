#!/usr/bin/env python3
"""Offline issue #43 contract regression checks (not a Maven/Spring runtime test)."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
source = root / 'src/main/java/info/trizub/clamav/webclient'
api = (source / 'api/ApiController.java').read_text()
rules = (source / 'api/ApiEndpointRules.java').read_text()
handler = (source / 'api/ApiExceptionHandler.java').read_text()
v1 = (source / 'api/v1/ApiV1Controller.java').read_text()
test = (root / 'src/test/java/info/trizub/clamav/webclient/api/Issue43LegacyApiIntegrationTest.java').read_text()
readme = (root / 'README.md').read_text()
openapi = (root / 'src/main/resources/openapi/clamav-v1.yaml').read_text()
guide = (root / 'docs/COMPLETE_USER_GUIDE.md').read_text()
checks = [
    ('shared endpoint lookup class added', 'final class ApiEndpointRules' in rules),
    ('missing endpoint is HTTP 404', 'HttpStatus.NOT_FOUND' in rules),
    ('missing default endpoint is 503', 'HttpStatus.SERVICE_UNAVAILABLE' in rules),
    ('disabled engine checked centrally', 'if (!endpoint.isEnabled())' in rules),
    ('legacy path uses shared lookup and guard', 'ApiEndpointRules.requireEnabled(ApiEndpointRules.resolve(endpoints, req.endpointId))' in api),
    ('legacy upload uses shared lookup and guard', 'ApiEndpointRules.requireEnabled(ApiEndpointRules.resolve(endpoints, endpointId))' in api),
    ('v1 endpoints use the shared resolver', 'ApiEndpointRules.resolve(endpoints, id)' in v1),
    ('v1 scans use shared enabled guard', v1.count('ApiEndpointRules.requireEnabled(ep);') == 2),
    ('legacy clamdtop validates endpoint outside connection failure handling', bool(re.search(
        r'ApiEndpointRules\.resolve\(endpoints, endpointId\);\s*Map<String, Object> snapshot;\s*try\s*\{\s*snapshot = clamdTop\.snapshot\(selected\.getId\(\)\)', api))),
    ('legacy path body validates blank/absent fields', '@Valid @RequestBody PathScanRequest req' in api),
    ('upload explicitly rejects empty submission', 'if (created.isEmpty())' in api),
    ('no raw exception classes stored in audit details', 'e.getClass().getSimpleName()' not in api),
    ('submission errors audited with stable FAILED outcome', api.count('"FAILED", selectedEndpointId, null') == 2),
    ('exception handler covers only legacy controller', 'assignableTypes = ApiController.class' in handler),
    ('malformed JSON returns 400', 'HttpMessageNotReadableException.class' in handler),
    ('multipart validation failures return 400', 'MissingServletRequestPartException.class' in handler),
    ('endpoint mismatches return 400', 'MethodArgumentTypeMismatchException.class' in handler),
    ('unknown resource returns JSON not_found', '"not_found"' in handler and 'HttpStatus.NOT_FOUND' in handler),
    ('response status exceptions get JSON', '@ExceptionHandler(ResponseStatusException.class)' in handler),
    ('max upload size maps to 413', 'HttpStatus.PAYLOAD_TOO_LARGE' in handler),
    ('unexpected errors are sanitized', '"internal_error", "Internal Server Error"' in handler),
    ('errors disable cache storage', '"Cache-Control", "no-store"' in handler),
    ('tests assert unknown id is 404', 'unknownEndpointReturnsJson404ForPathUploadAndMetricsWithoutEnqueueing' in test),
    ('tests assert disabled engine is 400', 'disabledEndpointReturnsJson400ForLegacyAndV1BeforeSubmission' in test),
    ('tests assert no enqueuing', test.count('assertThat(jobs.count()).isEqualTo(before)') >= 2),
    ('tests check malformed JSON and missing jobs', 'commonClientErrorsReturnSafeJsonInsteadOf500OrRawException' in test),
    ('readme contains migration warning', '2027-12-31' in readme and '/api/v1/scans/uploads' in readme),
    ('openapi mentions deprecation and date', '2027-12-31' in openapi),
    ('complete guide mentions deprecation', '2027-12-31' in guide),
    ('standalone migration guide exists', (root/'docs/ISSUE43_LEGACY_API_PARITY.md').exists()),
    ('CI regression workflow included', (root/'.github/workflows/ci.yml').exists() and 'mvn -B -ntp clean verify' in (root/'.github/workflows/ci.yml').read_text()),
]
for i, (name, passed) in enumerate(checks, 1):
    assert passed, f'FAIL {i}: {name}'
    print(f'PASS {i}: {name}')
print(f'PASS {len(checks)}/{len(checks)} issue #43 offline source checks (not Maven/Spring integration tests)')
