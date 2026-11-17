#!/usr/bin/env python3
"""Issue #44: focused offline source regression assertions (not a Maven test)."""
from pathlib import Path
import re
import json

root=Path(__file__).resolve().parents[1]
def read(path): return (root/path).read_text()
checks={
  'dynamic LDAP uses pinned identity, not URL': 'ldap.getDirectoryId()' in read('src/main/java/info/trizub/clamav/webclient/identity/DynamicLdapAuthenticationProvider.java') and 'ldap.getUrl()+"/"+ldap.getBaseDn()' not in read('src/main/java/info/trizub/clamav/webclient/identity/DynamicLdapAuthenticationProvider.java'),
  'identity is pinned before accepting LDAP logins': bool(re.search(r'pinLdapIdentity\(\);\s*revision\.incrementAndGet\(\);', read('src/main/java/info/trizub/clamav/webclient/identity/AuthenticationSettingsService.java'))),
  'legacy hash input preserved byte-for-byte': re.search(r'String\s+initial\s*=\s*explicit\.isEmpty\(\)\s*\?\s*url\s*\+\s*"/"\s*\+\s*baseDn\s*:\s*explicit', read('src/main/java/info/trizub/clamav/webclient/identity/LdapDirectoryIdentity.java')) is not None,
  'immutable directory id is persisted': 'INSERT INTO ldap_directory_identity' in read('src/main/java/info/trizub/clamav/webclient/identity/LdapDirectoryIdentity.java'),
  'existing id cannot silently change': 'if (!explicit.isEmpty() && !explicit.equals(existing))' in read('src/main/java/info/trizub/clamav/webclient/identity/LdapDirectoryIdentity.java'),
  'Configuration Center rejects identity rotation at review/apply': 'identities.pinnedLdapDirectoryId()' in read('src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCenterService.java'),
  'legacy settings include directory id': '"directoryId"' in read('src/main/java/info/trizub/clamav/webclient/identity/AuthenticationSettingsService.java'),
  'bootstrap and pinned value are kept separate': 'getConfiguredDirectoryId()' in read('src/main/java/info/trizub/clamav/webclient/identity/IdentityProperties.java'),
  'StartTLS uses strict default verifier': 'return new DefaultTlsDirContextAuthenticationStrategy();' in read('src/main/java/info/trizub/clamav/webclient/identity/IdentityLdapConfiguration.java') and '.setHostnameVerifier(' not in read('src/main/java/info/trizub/clamav/webclient/identity/IdentityLdapConfiguration.java'),
  'cert mismatch verified at the JNDI wire level': 'SSLPeerUnverifiedException' in read('src/test/java/info/trizub/clamav/webclient/identity/StartTlsJndiMismatchProbe.java'),
  'LDAP principal continuity test': 'upgradePinsHistoricalIssuerExactlyAndKeepsPrincipalAcrossEndpointAndBaseChange' in read('src/test/java/info/trizub/clamav/webclient/identity/Issue44LdapDirectoryIdentityTest.java'),
  'explicit directory ID rotation rejection test': 'explicitIdCanBeSelectedOnFirstSetupButCannotBeRotatedLater' in read('src/test/java/info/trizub/clamav/webclient/identity/Issue44LdapDirectoryIdentityTest.java'),
  'Configuration Center guard test': 'alreadyPinnedDirectoryIdCannotBeChangedThroughConfigurationCenter' in read('src/test/java/info/trizub/clamav/webclient/identity/LdapConfigurationUiTest.java'),
  'integration migration expectation updated for V12': 'getVersion().getVersion()).isEqualTo("12")' in read('src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationConsumerWiringTest.java'),
}
for vendor in ('h2','postgresql','mysql','mariadb'):
 s=read(f'src/main/resources/db/migration/{vendor}/V8__ldap_directory_identity.sql')
 checks['V8 migration '+vendor]='CREATE TABLE ldap_directory_identity' in s and 'directory_id VARCHAR(2048)' in s
cat=json.loads(read('src/main/resources/configuration-center/catalog.json'))
fields=[x for x in cat['fields'] if x['key']=='clamav.identity.ldap.directory-id']
checks['Configuration Center catalog field registered']=len(fields)==1 and 'CLAMAV_IDENTITY_LDAP_DIRECTORY_ID' in fields[0]['environment'] and fields[0]['legacy']=='directoryId'
checks['Configuration Center catalog has no duplicate keys']=len(set(f['key'] for f in cat['fields']))==len(cat['fields'])
for label,success in checks.items():
 print(('PASS' if success else 'FAIL')+' '+label)
if not all(checks.values()):raise SystemExit(1)
print(f'PASS issue #44 offline checks {len(checks)}/{len(checks)}')
