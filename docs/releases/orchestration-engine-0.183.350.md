# Orchestration Engine 0.183.350

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

The Server `v1.6.537` assembly pairs Engine `v0.183.350` with Web Console `1.6.190`.
Server publication and health evidence are maintained separately in
[its release notes](https://github.com/PastureStack/server/blob/main/docs/releases/server-1.6.537.md).
The immutable producer is `9c884710e5ea3d4136d80a870d9de9485674f3c3`
(tree `ca5f55302aaf7c33513134831b2790cee8e4678a`). The official release
packager produced `cattle.jar` SHA-256
`637ddaa42cd93c019b4d0813d7053b71dc42797a7f3ac2aa4b10fcc7e304cb31`.
The refreshed source projection covers 367 inputs and retains the exact same
1,371 operation tuples and admission semantics. Packaged Server acceptance and
deployment are recorded separately, not inferred from component publication.

There is no database migration. Code rollback must retain current workload data,
including archive metadata and existing API Key policy state; do not restore an
older database to roll back code.
