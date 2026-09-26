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

The latest public Engine release is
[`v0.183.322`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.322).
It resumes an interrupted account purge when a network is already removing,
and its release archive gate rejects development-only Engine files. See the
[release note](docs/releases/orchestration-engine-0.183.322.md) for behavior,
tests, and compatibility details. Previous release notes remain in
[`docs/releases`](docs/releases), and the
[GitHub release history](https://github.com/PastureStack/orchestration-engine/releases)
records published artifacts.

The next Engine candidate is `0.183.323`. It updates the packaged FreeMarker
dependency to `2.3.35` for CVE-2026-84939. See the
[candidate release note](docs/releases/orchestration-engine-0.183.323.md).

The build retains Java 25, Ubuntu 26.04, Maven, Liquibase, MariaDB/MySQL,
WebSocket, concurrency, and runtime maintenance. It consumes the exact
`5.7.4` JAR from
[`distributed-cache-runtime`](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.4)
and verifies its pinned digest and dependency metadata before installing it
into the build-local Maven repository. See
[`third-party/HAZELCAST.md`](third-party/HAZELCAST.md) for provenance.

## Build and validation

Before publishing an Engine artifact or a Server image, run the complete
JDK 25 package gate:

```sh
bash scripts/check-cattle-jdk25-full-package
```

After the gate passes, package and check the release artifact:

```sh
ENGINE_VERSION=0.183.323 bash scripts/build --release
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
