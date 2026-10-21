# Orchestration Engine 0.183.351

## API Key write completion

A request admitted before revocation could wait for a database row lock and
then complete its synchronous write using the stale admission snapshot. The
shared write delegate now runs a completion authorization hook inside the
existing Spring write transaction. Its InnoDB locking read observes the Key's
current state, removal, owner, kind, policy revision and expiry; a changed grant
returns a stable 403 reason and rolls back the write. The audit completion
records that denial without adding a duplicate admission event.

Full and legacy Keys gain no operation-registry, ancestry or read-grant
restriction. Exact self-policy updates must pass the existing policy manager's
logical lock and SQL compare-and-swap before they can complete. Self revocation
and removal remain authority-reducing operations. No new pre-manager SQL lock
reverses the established policy-management lock order.

Queued processes and subscriptions retain their existing live checks. Owner
role/membership admission is unchanged: this patch does not claim atomic
serialization of every concurrent owner role change with every database write.

## Audit and DEBUG logging

Ordinary audit request and response payloads are normalized to copied maps and
sanitized recursively. Authentication carriers, driver `secretValues`, compose
text and configured secret values are omitted. Current and previous environment
maps retain variable names with redacted values. Certificate `key` is removed
in certificate contexts while unrelated label keys remain visible. Existing
API Key audit metadata remains on its bounded whitelist.

The custom jOOQ listener no longer inherits value-printing callbacks. It logs
operation type, batch count, affected rows and elapsed time only: no SQL text,
binds, records, results or exception messages. This is not retrospective removal
of historical logs and does not detect a secret deliberately embedded in an
unrelated name or description.

The audit outbox preserves the administrator-selected JVM directory and the
existing data-volume default. A shared storage boundary pins the canonical root
and Linux directory handle, enforces private ownership, refuses linked or
nonregular event files, creates temporary files exclusively and bounds reads on
the opened handle. Existing event names and first-terminal replay are retained.
Trusted ancestors remain an operator prerequisite, including initial directory
creation. No CodeQL query, threshold or required check is relaxed.
See [audit storage requirements](../api-key-policy.md#audit-storage) before
upgrading a custom directory with unsafe ownership or write permissions.

## Validation and upgrade boundaries

The Dapper build still resolves packages against signed, fixed Ubuntu
snapshot indexes. An official archive may supply the same package bytes only
after validation against that snapshot's SHA256 and size; installation uses
the verified local cache. That transport fallback does not select new versions
or relax TLS, signature verification or release checks.

Both build recipes explicitly update four security inputs: Docker CLI 29.7.2
is rebuilt with Go 1.27.3 from the digest-pinned official bookworm builder;
Temurin stays on JDK 25 with the SHA256-pinned 25.0.4.1+1 archive; OpenSSL,
libssl3t64 and openssl-provider-legacy are pinned to Ubuntu
3.5.5-1ubuntu3.7 from the signed 20261002 security snapshot; curl,
libcurl3t64-gnutls and libcurl4t64 are installed as complete SHA256-pinned
Ubuntu 8.18.0-1ubuntu2.8 packages. The old curl 8.18.0-1ubuntu2.4 pin is only
the 20260826 base-snapshot bootstrap, replaced before package inventory and
build downloads. Build-time readback checks the final versions.

These input pins reuse the official fixes already packaged by Server 539
(JDK, curl and OpenSSL) and LB 0.9.32 (Go 1.27.3); those component receipts
do not prove a new Engine builder or cattle.jar passes. The required CodeQL,
raw HIGH/CRITICAL vulnerability and release checks remain unchanged. This
source note is neither publication nor a scan or native-acceptance result.

Focused source tests cover the write SPI, actual production transaction advice,
current-read validation, exact self-policy CAS and aliases, nearest mutation,
queued-process and subscription regressions, recursive audit payloads and jOOQ
DEBUG/TRACE output. Release packaging and native row-lock, role, OIDC and Server
acceptance remain separate gates; an isolated test result must not be promoted
to a whole permission-matrix pass.

There is no database migration. Roll back code using the retained previous
Server image and the same persistent volumes; do not roll back the database or
API Key policy records. Do not patch deployed binaries, disable MFA or change
the external reverse proxy to apply this correction.
