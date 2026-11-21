# Issue #23 — bounded login and scan limiters

This complete source retains fixes #13–#22. Login failure and scan limiter state
now has a strict configurable capacity, indexed TTL expiry and scheduled bounded
cleanup. New keys are rejected at capacity; live lockouts/budgets are retained
instead of being evicted by random-identity churn.

Scan submission throttling runs after authentication and uses token/account IDs,
source-qualified external principals or an anonymous IP fallback. Rotating raw
Authorization headers no longer creates fresh buckets. Basic and browser scans
share the local account's budget across routes and client addresses. Invalid
credentials rejected earlier do not allocate scan state or permit a scan.

The account-wide five-failure/15-minute lockout introduced in #14 is preserved.
The new bounded settings are in Configuration Center and accept deployment
overrides. No schema migration or new dependency is required.

See [policy, capacity behavior, settings and tests](docs/BOUNDED_LIMITERS.md) and
[current results](docs/security/bounded-limiters-validation.json).
