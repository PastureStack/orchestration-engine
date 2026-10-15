# Orchestration Engine 0.183.350 release candidate

Stack and Service resources gain an opt-in `archived` flag on v1 and v2-beta.
Older resources without the field remain unarchived. A separate archive update
requires existing update permission, stable inactive services and stopped
related containers. System and infrastructure workloads, mixed configuration
updates and create-with-archive requests are rejected.

Archive metadata does not stop, delete or recreate workloads. Lifecycle and
association guards require restoring an archived resource and its parent before
starting it or adding children. Restoring a parent preserves independently
archived children. Existing stop, delete and API Key permission boundaries remain
in effect. Mounted-directory and volume contents still require operator backups.

## Verification and publication status

The affected-path evidence includes 63 focused backend tests, 31 core native API
checks, 88 role/API Key cases across both API roots, 5 role browser cases and
6 native plus 4 browser audit checks. Web acceptance includes 37 focused tests,
52 locale/theme/viewport layouts and 7 interactions. Unchanged passing evidence
is retained rather than rerun.

A dedicated Ubuntu 26.04 / Docker 29.8 host completed official first-host
registration and automatic Default infrastructure readiness. A normal CNI
workload passed DNS, egress and health checks. A real running service was denied
archive. After stop, Service/Stack archive and restore preserved the Docker
identity, configuration, mounts and bind/volume data hashes; native reactivation
reused that instance with healthy networking and unchanged data. Packaged release
verification and Server deployment remain separate gates. This is a bounded
archive acceptance result, not an assertion that every platform feature was retested.

Server `v1.6.537` is planned to package this candidate with Web Console `1.6.190`.
Producer commit, artifact SHA-256, publication and deployment are pending and must
be recorded from the final immutable artifacts. The prior published Engine
identity is retained in [the compatibility contract](../../COMPATIBILITY.md).

There is no database migration. Code rollback must retain current workload data,
including archive metadata and existing API Key policy state; do not restore an
older database to roll back code.
