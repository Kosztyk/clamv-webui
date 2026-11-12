# Bounded login and scan throttling (issue #23)

Login failure records and scan rate-limit buckets now have a strict capacity
and TTL expiry. Raw Authorization headers are never stored or hashed into a
bucket key. Account-wide login protection from issue #14 is retained.

## Login failures

Local form and HTTP Basic share five failed passwords per normalized local
account within 15 minutes, independent of client IP. LDAP has a separate realm
in the same bounded store. The fifth failure starts a full 15-minute lockout;
requests during lockout do not extend it. A successful authentication before
lockout removes the record. Existing authenticated sessions remain usable.

The stored key contains the realm and a fixed-length SHA-256 digest of the
trimmed, lower-case username. Missing and literal `null` usernames remain
separate keys. Names longer than 1024 characters and unsupported realms are
rejected without allocating state. Local account names remain subject to their
existing smaller database limit.

Expired partial-failure and locked records are removed, even without a successful
login. The existing fixed lockout is preserved: this change introduces no sleeps
on servlet threads and does not replace the established five-attempt policy
with progressive delays. Basic returns `429 login_locked` and `Retry-After`;
form login redirects to `/login?locked`.

## Scan submission identity

The scan filter runs **after authentication and before authorization**. It
covers browser `/scan/upload` and `/scan/path`, legacy `/api/scan/upload` and
`/api/scan/path`, and POST routes under `/api/v1/scans/`.

| Request identity | Shared budget |
|---|---|
| Scoped Bearer token | Immutable token ID, across header spelling and client IPs |
| Local browser session or Basic credentials | Immutable local account ID, across both mechanisms, routes and IPs |
| LDAP/OIDC session | Source-qualified canonical principal name |
| Anonymous request reaching the filter | Socket/trusted-proxy client IP only; arbitrary unrecognized headers do not create new buckets |

Different authenticated identities behind one NAT have independent budgets.
The default is **30 attempts per subject per 60-second window**, beginning at
that subject's first attempt. Crossing a wall-clock minute does not reset it.
Rejected requests do not extend the deadline or keep incrementing an unbounded
counter. A rejection returns `429 rate_limited`, `Retry-After` and
`Cache-Control: no-store`. Reads and non-scan actions do not allocate scan state.
Authenticated requests rejected later by role, scope or input validation still
consume a scan attempt.

Invalid Basic/Bearer credentials, TLS violations and missing CSRF tokens can be
rejected by earlier security filters. They do not acquire an authenticated scan
identity or allocate scan buckets. Invalid Bearer headers remain unauthorized;
rotating them never permits a scan. Anonymous requests that reach the rate filter
share the IP budget regardless of the raw header. Direct clients cannot change
their peer address using forwarded headers under the safe issue #13 defaults.

## Capacity and expiry

| Setting | Default | Configuration Center |
|---|---|---|
| `clamav.security.login.max-entries` / `CLAMAV_SECURITY_LOGIN_MAX_ENTRIES` | `10000` | Identity & API → Local recovery administrator → Advanced |
| `api.v1.scan-rate-limit-max-entries` / `API_V1_SCAN_RATE_LIMIT_MAX_ENTRIES` | `10000` | Identity & API → API tokens, CLI & MCP → Advanced |
| `api.v1.scan-rate-limit-per-minute` / `API_V1_SCAN_RATE_LIMIT_PER_MINUTE` | `30` | Existing API rate control |

The maximum-entry settings accept **1–100000** and require restart. They bound
both live records and their expiry index; login capacity includes both realms.
The scan count accepts **1–10000**. Invalid startup capacities/counts fail startup.
No database migration or additional cache dependency is required. Optional
Compose overrides belong on the existing web service:

```yaml
environment:
  CLAMAV_SECURITY_LOGIN_MAX_ENTRIES: "10000"
  API_V1_SCAN_RATE_LIMIT_MAX_ENTRIES: "10000"
  API_V1_SCAN_RATE_LIMIT_PER_MINUTE: "30"
```

A live record is **never evicted to admit another key**. At capacity, a new login
account or scan subject is temporarily rejected until expiry or a successful
tracked login frees space. Tracked subjects keep their counters and deadlines.
This avoids defeating protection by flooding an LRU cache with random names.
It also means a flood can temporarily deny a legitimate *untracked* account;
choose sufficient capacity and retain edge abuse controls. The application logs
a capacity warning at most once per minute per store, without usernames or
credentials. The response keeps the normal lockout/rate-limit shape; this is a
fail-closed overload condition.

An ordered expiry index avoids a full-map scan. A one-second scheduled task
removes at most 1024 expired entries from each store per run. On a full store,
a request can reclaim at most 16 expired entries, plus its own expired key.
Expiry/index changes cost logarithmic time in the bounded index; a shared-deadline
bucket uses a linked set so removing its first key does not scan sparse hash
storage. Atomic updates enforce the bound and counters under concurrency.
Inactive cleanup does not depend on incoming requests or successful logins.

State remains **per application instance**, resets on restart, and is not a
distributed throttle. This does not add supported multi-replica operation. Use
external ingress controls for larger distributed attacks and continue to keep
ClamD private. Rebuild/recreate the application to apply the fix.

## Verification

```sh
mvn verify
python3 tools/verify-bounded-limiters.py --output /tmp/bounded-limiters.json
```

Tests exercise one million distinct account names, one million rotating headers,
one million distinct scan peers, and one million store keys with small capacities.
They check both storage bounds, retention of existing budgets, concurrency,
expiry, bounded cleanup work, Basic/session sharing, stable token identities,
separate users behind one IP, and actual Configuration Center consumer binding.
The packaged runner uses the real JAR, Spring Security and isolated H2, with
synthetic ClamD replies and explicit loopback trusted proxy headers. It verifies
both normal authentication and fail-closed cache saturation. See the
[current validation record](security/bounded-limiters-validation.json).
