# PastureStack Orchestration Engine

Orchestration Engine coordinates metadata, API, scheduling, storage,
networking, and resource lifecycle for the preserved control platform.
PastureStack is an independent community effort to preserve, audit, and
modernize the Rancher 1.6 ecosystem. It is not affiliated with or endorsed
by Rancher Labs or SUSE.

**Upstream:** [`rancher/cattle`](https://github.com/rancher/cattle). This fork
preserves upstream history, authorship, dates, tags, licenses, and bundled
dependency notices. PastureStack maintenance is consolidated after the
preserved upstream boundary.

## Current release

The current published release is `v0.183.327`.
Certificate name/description updates preserve omitted certificate content;
deleting or removing a certificate referenced by a v2 load balancer's alternate
list is rejected just like a default certificate. Authorization and private-key
masking are unchanged. See the
[release note](docs/releases/orchestration-engine-0.183.327.md) for behavior,
tests, and compatibility details. Previous release notes remain in
[`docs/releases`](docs/releases), and the
[GitHub release history](https://github.com/PastureStack/orchestration-engine/releases)
records published artifacts.

The official numeric [release `v0.183.327`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.327)
is published from source commit `dce2f2473ffea1510fe10676a771eb1fe5d0b161`.
Its `cattle.jar` WAR has SHA-256
`c6d4c3003a19db19d1be73e69aa52358a0a4166bf726cbefe7e2ab9ed5664b56`;
the remote published asset's hash was independently read back and matched.
[Official build/security run `36701559252`](https://github.com/PastureStack/orchestration-engine/actions/runs/36701559252)
and [CodeQL run `36701559287`](https://github.com/PastureStack/orchestration-engine/actions/runs/36701559287)
passed, along with all 17 focused Certificate tests. The exact published WAR
completed standalone startup against isolated H2 with JDK `25.0.3` and exited
with code 0. Full MariaDB/MySQL integration and Server/browser Certificate QA
remain separate and pending; the full resource/role matrix is not accepted
by these component checks.

The `v0.183.324` tag is source-only and has no published release artifact.

The build retains Java 25, Ubuntu 26.04, Maven, Liquibase, MariaDB/MySQL,
WebSocket, concurrency, runtime maintenance, and the existing direct tool
versions. Its signed Ubuntu security snapshot
pins OpenSSL CLI, library and legacy provider to `3.5.5-1ubuntu3.6`, the
official fix for [CVE-2026-84782](https://ubuntu.com/security/CVE-2026-84782).
It retains OpenSSL 3.5 and does not relax the build-image security gate.
It consumes the exact
`5.7.4` JAR from
[`distributed-cache-runtime`](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.4)
and verifies its pinned digest and dependency metadata before installing it
into the build-local Maven repository. See
[`third-party/HAZELCAST.md`](third-party/HAZELCAST.md) for provenance.

## Unreleased 0.183.328 candidate

The source candidate updates platform Jackson to `2.22.3` and the isolated
WebAuthn/logging Jackson line to `3.2.3`, with patchless annotations remaining
`2.22`. These are the official patched versions for CVE-2026-91776 and
CVE-2026-91777; see the [candidate note](docs/releases/orchestration-engine-0.183.328.md).
The candidate pins the corresponding officially published
[`distributed-cache-runtime` `5.7.5` artifact](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.5),
because `5.7.4` embeds both older Jackson versions. The actual release JAR,
source commit, checksum, and asset ID were read back and verified; see the
[Cache provenance](third-party/HAZELCAST.md). Engine328 has not yet been built
or published, and its seven new Java regression cases have not yet been
compiled or executed. Cache's producer checks are not Engine validation.
No Engine328 artifact, CI PASS, Server496 digest, or deployment is claimed.
The published 327 provenance above remains historical and unchanged.

## Build and validation

Before publishing an Engine artifact or a Server image, run the complete
JDK 25 package gate:

```sh
bash scripts/check-cattle-jdk25-full-package
```

After the gate passes, package and check the release artifact:

```sh
ENGINE_VERSION=0.183.328 bash scripts/build --release
bash scripts/check-release-artifact dist/artifacts/cattle.jar
```

The gate builds every Maven module, checks dependency hygiene and packaged
classes, and starts the standalone application against isolated H2. Full
database and platform checks require isolated MariaDB/MySQL and companion
services. See [COMPATIBILITY.md](COMPATIBILITY.md), [SECURITY.md](SECURITY.md),
and [ORIGIN.md](ORIGIN.md) for those boundaries and source provenance.

## Language and licensing

The web console supplies user-facing translations. API fields, persisted
values, event names, identifiers, and remote error payloads remain
compatibility data and are not translated.

The inherited project remains under the [Apache License 2.0](LICENSE).
Inherited work and bundled dependencies retain their own attribution and
licenses.
