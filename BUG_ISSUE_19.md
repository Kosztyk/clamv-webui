# Issue #19 — bounded upload names and short database transactions

This complete source edition retains fixes #13–#18 and addresses [issue #19](https://github.com/Kosztyk/clamv-webui/issues/19).

- Preserve submitted names as bounded readable job labels; store uploads under ID-prefixed ASCII components of at most 153 bytes. Both 400-character ASCII and Unicode names are covered.
- Name quarantined uploads by job ID and PATH/WATCH copies by independent IDs. A 255-byte source component can be moved without extending that name.
- Persist quarantine warnings on the job and expose `FAILED` / `QUARANTINE_FAILED` through both job APIs while retaining `VIRUS_FOUND` and protecting private paths.
- Copy and hash in one pass outside database transactions. Commit the complete batch in a separate short transaction, then enqueue scans. Disable Open EntityManager in View by default and reject ambient upload transactions before opening streams.
- Preserve private permissions, active-file leases, failed-batch/rollback cleanup, post-commit queue recovery and upload retention from #18.

The full Java suite passed with **703 passed, 5 existing skipped, zero failures/errors**. This adds six cases, including a one-connection pool test with three blocked copies and another query. Real ClamAV runtime checks passed for a 400-character clean upload, forced quarantine failure on EICAR, upload deletion and private-state/read-only mount isolation. All 36 packaged Configuration Center checks passed.

No schema migration or configuration catalogue field is added. Application version remains 2.1.0. Docker image execution was not available locally; native Linux runtime and rendered-Compose evidence are identified separately in the validation record. No GitHub push or release publication was performed.

See [behavior and upgrade details](docs/UPLOAD_FILENAMES.md), [current validation](docs/security/upload-filenames-validation.json), and the cumulative [changed-file list](CHANGED_FILES.md).
