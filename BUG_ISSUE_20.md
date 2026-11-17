# Issue #20 — bounded ClamD network work and scheduler pool

This complete source retains fixes #13–#19 and addresses [issue #20](https://github.com/Kosztyk/clamv-webui/issues/20).

- Configure an explicit four-thread scheduler, adjustable from 4–64, including when virtual threads are enabled.
- Replace the unbounded library TCP client with nonblocking TCP/Unix transports sharing deadlines, framing, scan parsing and a 4 MiB response cap.
- Share a five-second budget across each health snapshot's PING/VERSION/STATS; include bounded DNS and a three-second connection budget.
- Keep scan operations on a separate configurable total budget, with a timeout on stalled upload writes. Route native upload MULTISCAN through the same client.
- Return existing API/page failure responses promptly, close network resources and classify timeout scan metrics correctly.

The Java suite passed with **729 passed, 5 existing skipped, zero failures/errors** (734 total; 26 new cases). Tests cover a real TCP peer that never answers, trickled replies, write backpressure, response caps, independent scan budgets, DNS saturation, HTTP surfaces and scheduled-task progress. Real ClamAV and packaged application verification are described with their scope/limits in the validation record.

No schema migration or new Configuration Center field is added. Application version remains 2.1.0. The complete archive includes exact changed-file lists and retains previous validation reports with their original scope. Docker image execution and live external SQL engines were not run locally for this issue; no GitHub push or release publication was performed.

See [settings, behavior and upgrade details](docs/CLAMD_TIMEOUTS.md), [current validation](docs/security/clamd-timeouts-validation.json), and [the cumulative change list](CHANGED_FILES.md).
