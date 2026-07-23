# PastureStack Orchestration Engine

Orchestration Engine provides the metadata, process, API, scheduling, storage, networking, and lifecycle coordination layer for the preserved control platform.

PastureStack is an independent community effort to preserve, audit, and modernize the Rancher 1.6 ecosystem. It is not affiliated with or endorsed by Rancher Labs or SUSE.

**Upstream:** [`rancher/cattle`](https://github.com/rancher/cattle). This GitHub fork preserves upstream history, authorship, dates, tags, licenses, and bundled dependency notices. PastureStack maintenance is consolidated into one commit after the preserved upstream boundary.

## Project status

This source tree produces engine version `0.183.269`. It retains the existing Java 25, Ubuntu 26.04, Maven, Liquibase, MariaDB/MySQL, WebSocket, dependency, concurrency, and runtime-hardening work from the maintained compatibility line. Release builds also compile a pinned Hazelcast `5.7.0` source revision with the tracked Jackson security patch described in [`third-party/HAZELCAST.md`](third-party/HAZELCAST.md); the patched artifact is never downloaded from an unverified binary source.

Product-facing names use PastureStack terminology. Established Java packages, Maven coordinates, database identifiers, settings, API schemas, event names, Docker labels, and executable aliases remain where changing them would break compatible installations.

## Build and test

Run the complete JDK 25 package gate before publishing an engine artifact or Server image:

```sh
bash scripts/check-cattle-jdk25-full-package
```

The gate performs dependency-hygiene checks, builds every Maven module with JDK 25, rejects retired packaged libraries, verifies class-file major version `69`, and starts the standalone application against an isolated H2 database.

To create the complete release archive after the gate passes:

```sh
ENGINE_VERSION=0.183.269 bash scripts/build --release
bash scripts/check-release-artifact dist/artifacts/cattle.jar
```

Release packaging uses the exact Git revision and commit timestamp as reproducible build inputs. The selector accepts only a complete web application containing the launcher, Runtime resources, authentication logic, and `WEB-INF/web.xml`.

Database-backed and full-stack suites require isolated MariaDB/MySQL and companion-service fixtures. See [COMPATIBILITY.md](COMPATIBILITY.md), [SECURITY.md](SECURITY.md), and [ORIGIN.md](ORIGIN.md).

## Language support

User-facing translations are supplied by the PastureStack web console. API field names, persisted values, event names, identifiers, and remote error payloads are compatibility data and are not translated.

## License and attribution

The inherited project remains licensed under [Apache License 2.0](LICENSE). Copyright and attribution for inherited work and bundled dependencies remain with their respective authors and contributors. PastureStack contributors claim authorship only for their own changes.
