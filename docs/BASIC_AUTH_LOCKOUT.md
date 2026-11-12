# HTTP Basic password lockout — issue #14

HTTP Basic retains its existing authentication routes and HTTPS requirement. It now participates in password lockout before credentials are checked, including requests to the legacy `/api/**` routes and protected GUI routes.

## Policy

| Behavior | Result |
|---|---|
| Failed passwords | Five failures within 15 minutes lock the account |
| Locked account | HTTP Basic returns `429`, JSON error `login_locked`, `Cache-Control: no-store` and `Retry-After` |
| Correct password during lockout | Also rejected; authentication is not attempted |
| Lockout duration | 15 minutes from the failure that reaches the threshold |
| Additional blocked requests | Do not extend the lockout |
| Successful authentication before lockout | Clears the account's failed-attempt counter across all IPs |
| Basic versus local form | Share the local account's budget, preventing route alternation |
| Different client IPs | Share the same account budget, preventing address rotation |
| LDAP | Separate account-wide budget in the LDAP realm |
| Other users | Independent counters |
| Bearer tokens and OIDC | Bearer tokens do not use this password counter; OIDC protection remains with the identity provider |

Form login keeps its existing `/login?locked` redirect. HTTP Basic is rejected over plaintext HTTP before password authentication; adding a client-supplied HTTPS header does not change the safe direct-access defaults from issue #13. Proxy deployments still require explicit native forwarding and a narrow proxy allowlist.

## Implementation

`BasicLoginLockoutFilter` uses Spring's Basic credential converter to obtain the username and checks the local counter before `BasicAuthenticationFilter`. It marks Basic authentication details and records actual Spring authentication success/failure events. HTTP response codes are not used to guess whether authentication succeeded; an authenticated request denied by authorization still counts as a successful password check. The marker prevents existing form and LDAP failure handlers from being counted twice. The filter is registered only inside Spring Security.

`LoginAttemptService` keys the budget by realm and normalized username, excluding the remote address. Counts and expiry updates are atomic per account. The five-attempt and 15-minute values remain built in. Issue #23 adds a strict configurable capacity, hashed account keys and indexed/scheduled expiry; new keys are temporarily throttled at capacity, while live lockouts remain intact. This needs no database migration. See [bounded limiter behavior and settings](BOUNDED_LIMITERS.md).

Counters are in process memory and reset on application restart. They are not shared across replicas. This does not replace the application's existing single-active-instance deployment constraint or an external provider's own abuse controls. Existing authenticated browser sessions are not revoked when an account reaches password lockout.

## Validation

`BasicAuthenticationLockoutIntegrationTest` sends real HTTP requests through embedded Tomcat and the complete Basic authentication chain, using an explicitly allowlisted loopback proxy to exercise HTTPS termination. It covers:

- Failure counting and correct-password rejection after five failed attempts.
- Different forwarded client IPs and protected routes sharing the lockout.
- Successful Basic and local form login resetting the shared failure counter.
- Alternating Basic/form failures reaching one combined threshold, and locked form login being refused.
- Plain HTTP and malformed Basic credentials; other users, LDAP counters and Bearer tokens remaining independent.

`LoginAttemptServiceTest` uses a controllable clock to verify failure-window expiry, a full lockout interval from the threshold, successful reset across IPs and blocked requests not extending the interval.

```bash
mvn -Dtest='BasicAuthenticationLockoutIntegrationTest,LoginAttemptServiceTest,ApiToken*IntegrationTest' test
```

The cumulative archive retains the issue #13 regressions and deployment documentation. See `docs/security/basic-auth-lockout-validation.json` for the completed build/test results and limits.
