# Orchestration Engine 0.183.327

This release fixes two shared Certificate API lifecycle paths. It preserves
the authorization, private-key masking, and v1 schema changes from 0.183.326.

## Partial updates

The Certificate update filter previously parsed `cert` even when a request
omitted that field. Name-only and description-only updates therefore failed
with HTTP 422. The filter now derives certificate metadata only when the
request explicitly contains `cert`. The shared schema rejects explicit
`cert: null` with HTTP 422 / `NotNullable` before the certificate filter runs.
Non-null empty or malformed values reach the filter and fail with HTTP 422 /
`InvalidFormat`. Create validation is unchanged.
An omitted certificate or private key is not fetched and resubmitted by the
filter. No schema, authentication, or private-key output policy is changed.

## Load balancer reference protection

The shared DELETE / remove-action guard now reads the actual alternate IDs
from a v2 `lbConfig.certificateIds` list, rather than adding an empty local
list to itself. Alternate and default references in non-removed load balancer
services in the certificate's account block removal with the existing
HTTP 405 / InvalidAction response. The v1 helper path and the query's
account, removed, and kind constraints are preserved. Unused certificates
can still be deleted. Account teardown process behavior is not changed.

## Focused regression coverage and runtime acceptance

The Dapper builder retains its base package snapshot and direct tool versions.
After that install it consumes a signed `20260930T000000Z` Ubuntu
`resolute-security` snapshot, exact-pinning `libssl3t64`, `openssl` and
`openssl-provider-legacy` to `3.5.5-1ubuntu3.6`. This is the official Ubuntu
[CVE-2026-84782 fix](https://ubuntu.com/security/notices/USN-8847-1), on the
existing OpenSSL 3.5 line. Package inventory records both snapshots. No
High/Critical exemption or security-gate relaxation is added.

Two JUnit4 classes exercise the real API filters. Nine partial-update/create
tests cover omitted, explicit invalid, and valid certificate input and the
ten derived metadata fields. Eight load-balancer tests each exercise DELETE
and the case-insensitive remove action, including alternate/default, v1/v2,
and unreferenced inputs. Downstream calls and query boundaries are asserted.
Restoring just the two old implementations makes three omitted-field update
tests fail with InvalidFormat and the v2 alternate-reference test reach the
downstream deletion manager. The patched filters pass all 17 focused tests.
These filter-level tests do not exercise the preceding shared-schema validation
of explicit null input.
The normal official build/security and CodeQL gates passed as recorded below.

## Published artifact evidence

The official numeric [release `v0.183.327`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.327)
is published from source commit `dce2f2473ffea1510fe10676a771eb1fe5d0b161`.
[Official build/security run `36701559252`](https://github.com/PastureStack/orchestration-engine/actions/runs/36701559252)
and [CodeQL run `36701559287`](https://github.com/PastureStack/orchestration-engine/actions/runs/36701559287)
passed. The published `cattle.jar` WAR has SHA-256
`c6d4c3003a19db19d1be73e69aa52358a0a4166bf726cbefe7e2ab9ed5664b56`;
the remote asset's hash was independently read back and matched. The exact
published WAR completed standalone startup against isolated H2 with JDK
`25.0.3` and exited with code 0. This does not establish full MariaDB/MySQL
integration or the platform resource/role matrix.

Server artifact and browser acceptance remain pending. Server acceptance must
independently verify both v1 and v2-beta name and
description edits on fresh, unmounted certificates, unchanged certificate/key
storage, native UI save/cancel/readback, and denied removal of referenced
alternate/default certificates. Focused tests alone are not full platform QA.

No database migration is required. Rollback to 0.183.326 restores both bugs;
retain the previous immutable Server image and the existing named volumes.
