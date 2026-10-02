# Orchestration Engine 0.183.330

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

The DAO uses MockConnection and the handler uses proxies in these tests.
This is not proof of real MariaDB concurrency, a published artifact, an
accepted Server deployment, or complete UI/resource/permission coverage.
Normal release builds, packaged-artifact checks, and real-host/browser
acceptance remain pending before this version is declared published.

## Compatibility and rollback

Authentication, authorization, cookie/session behavior, OIDC/MFA, nftables
and plugin ownership boundaries are unchanged. Existing numeric tags are
immutable. A consuming Server must retain its environment variables, data
volumes, restart policy, AppArmor and public origin. Rollback uses the
previous immutable Server image with the same preserved data; no SQL or
runtime patch is needed for this name-only change.
