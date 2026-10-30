# OIDC configuration compatibility (v2.0.0)

Issue #4 now supports the requested standard Spring configuration in the existing
native OIDC authorization-code flow. The OAuth2 client dependency, login button,
claim-to-role mapping and local recovery login are retained. No migration is needed.

## Standard variables

| Environment variable | Spring property | Active setting |
|---|---|---|
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID` | `spring.security.oauth2.client.registration.oidc.client-id` | Client ID |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET` | `spring.security.oauth2.client.registration.oidc.client-secret` | Client secret |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE` | `spring.security.oauth2.client.registration.oidc.scope` | Requested scopes |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI` | `spring.security.oauth2.client.provider.oidc.issuer-uri` | Trusted HTTPS issuer |

Supplying a nonempty standard issuer, client ID and client secret automatically
enables the SSO option. Scopes default to `openid,profile,email,groups` when omitted.
An incomplete standard registration stays disabled unless explicitly enabled.
Dotted properties and indexed scope lists in application configuration also work.
Issuer discovery is still delayed until the SSO action; an unavailable IdP does
not prevent startup or local authentication.

The internal registration and callback remain `clamav` for compatibility. Register
`https://YOUR_PUBLIC_HOST/login/oauth2/code/clamav` with your identity provider.
The `oidc` portion of the standard configuration keys selects the input properties;
it does not change the existing callback URL.

## Configuration precedence

1. Admin-saved authentication settings override startup settings after restart,
   including an explicitly disabled OIDC provider and saved scopes.
2. Explicit `clamav.identity.oidc.*` / existing `CLAMAV_IDENTITY_OIDC_*` values
   override the equivalent standard property. An explicit
   `CLAMAV_IDENTITY_OIDC_ENABLED=false` prevents automatic activation.
3. The four standard Spring properties supply otherwise missing startup values.

Legacy-only configuration keeps its existing explicit enable switch. Existing
client-secret files are retained and are not combined with a standard inline
secret. Leaving the secret field blank during an admin save retains its startup
source or previously encrypted replacement. Stored settings JSON and UI responses
do not contain the secret. Old saved JSON without scopes inherits startup scopes
until scopes are explicitly saved in the admin UI.

## Example

```yaml
services:
  clamav-web-client:
    environment:
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID: "clamav-webui"
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET: "${OIDC_CLIENT_SECRET}"
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE: "openid,profile,email,groups"
      SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI: "https://identity.example/realms/home"
      CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM: "groups"
      CLAMAV_IDENTITY_OIDC_GROUPS_VIEWER: "clamav-viewers"
      CLAMAV_IDENTITY_OIDC_GROUPS_OPERATOR: "clamav-operators"
      CLAMAV_IDENTITY_OIDC_GROUPS_ADMIN: "clamav-admins"
```

Supply the secret through the deployment environment. For a mounted secret file,
keep the existing `CLAMAV_IDENTITY_OIDC_CLIENT_SECRET_FILE` mechanism and explicit
`CLAMAV_IDENTITY_OIDC_ENABLED=true` instead of the standard inline secret.

## Scopes and roles

Scopes can be configured using the standard `..._SCOPE` variable,
`CLAMAV_IDENTITY_OIDC_SCOPES`, `clamav.identity.oidc.scopes`, or **Requested scopes**
under **Admin → Authentication → OIDC / SSO**. Comma-separated and space-separated
lists are accepted and duplicates are removed. The `openid` scope is required.
Invalid scope input cannot replace saved settings. Saving scopes refreshes the
client registration used for subsequent authorization requests.

Only request scopes your IdP supports. For a provider without a `groups` scope,
set `openid,profile,email` and arrange for the configured authorization claim to
be emitted by that provider. Requesting `groups` alone does not create group
memberships or grant an application role.

Set `CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM` to the intended claim (`groups`, `roles`,
or a nested path such as `realm_access.roles`). Map its values to explicit viewer,
operator and admin groups. Unmapped users receive no application access. Local
username/password login remains alongside SSO. Provider logout remains optional.

See [OIDC_CONFIGURATION_VALIDATION.md](OIDC_CONFIGURATION_VALIDATION.md) for the
completed validation and the limits of provider testing.

Spring property reference:
https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html
