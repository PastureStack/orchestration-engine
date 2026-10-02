# Orchestration Engine 0.183.330

## Published artifact and evidence

The official numeric [release `v0.183.330`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.330)
is published with a signed annotated tag pointing to exact CI source
`3f7320a8063a5471618be5b8be6a168f49559847`.
[PR #68](https://github.com/PastureStack/orchestration-engine/pull/68) was normally
squash-merged as `b76ddcc5c24a7683ffe05fba26707b71b3b313eb`; its tree is identical
to the CI source. The immutable release tag was not moved to the squash commit.
The exact `cattle.jar` WAR is `87,700,053` bytes with SHA-256
`c01cbbfd63625fc09f39c5494775919aad6db22c92050b217b28b940b57e1de3`.

All six exact CI assets are published: `cattle.jar`, `cattle.jar.sha256`,
`cattle-cyclonedx.json`, `security-summary.txt`, `source-revision.txt` and
`unit-test-results.json`. Anonymous content readback independently checked the
checksum, source revision and unit-test results. The public WAR was not
downloaded again; publication readback does not replace its exact CI artifact
or Server consumption checks.

[Build/security run `36966573025`](https://github.com/PastureStack/orchestration-engine/actions/runs/36966573025)
passed 268 suites / 1,141 tests with zero failures, errors or skips, including
all 19 native-name cases and six retained GenericObject cases. Packaged module
version 330 and the unchanged readonly/restricted frozen role-schema hashes
passed. Artifact and build-image Critical/High findings are zero. The
[CodeQL run `36966573050`](https://github.com/PastureStack/orchestration-engine/actions/runs/36966573050)
retains four lower-severity findings, with zero Critical/High findings; no
all-findings-zero claim is made.

The exact CI WAR completed standalone JDK `25.0.3` startup against isolated H2
and exited 0, with no network, exposed ports or platform-data mounts. An initial
test launch could not traverse its test-artifact directory after capabilities
were dropped; that failed test receipt is retained, and no product permissions
were changed. Component publication and isolated startup do not establish
Server deployment, real MariaDB concurrency or real-host/browser acceptance.

## Imported container names

Docker containers imported as independent native containers previously kept
their original platform display name after a Docker rename. This could make
different retained rollback containers appear to be duplicate entries.

The existing ping inventory now uses its UUID field only as a change hint.
The Engine obtains the authoritative current name by inspecting the exact
64-character Docker ID using the existing Agent inspect contract. It does
not resolve a container by a mutable name or UUID and does not create a new
instance, remove rollback containers, or change existing state-sync/import
processing. No Agent, Web Console, schema, database migration or plugin
contract change is required.

An update is limited to an existing, stable running/stopped, unmanaged native
container. Service, service-index, stack, system/managed-label, removed and
transitional records are excluded. The source Host/Agent/resource account
and reported host UUID must match, and the instance must have exactly one
nonremoved host mapping. Selection and the final update both check these
relations. The update sets only `instance.name` and compares every original
instance column, so a concurrent lifecycle or relationship change fails
closed without retry. Successful changes use the existing resource event;
a notification failure is distinguished from a failed database update.

## Focused validation

All 19 distinct focused offline cases have obtained PASS across incremental
runs: seven DAO cases, nine handler cases, and three monitor cases, including
the existing state-sync regression. Compilation and mock failures from the
initial attempts were retained; only affected cases were rerun. Coverage
includes exact-ID inspection, separate instances with the same hint, current
inspect authority over delayed hints, idempotence, source binding, managed
record exclusions, name-only/full-row CAS, and notification failures.

The DAO uses MockConnection and the handler uses proxies in these focused
tests. The separate normal CI, packaged-artifact, publication and isolated-H2
results are recorded above; mocks alone do not establish those results. Real
MariaDB concurrency, consuming Server deployment, real-host/browser acceptance
and complete UI/resource/permission coverage remain separate requirements.

## Compatibility and rollback

Authentication, authorization, cookie/session behavior, OIDC/MFA, nftables
and plugin ownership boundaries are unchanged. Existing numeric tags are
immutable. A consuming Server must retain its environment variables, data
volumes, restart policy, AppArmor and public origin. Rollback uses the
previous immutable Server image with the same preserved data; no SQL or
runtime patch is needed for this name-only change.
