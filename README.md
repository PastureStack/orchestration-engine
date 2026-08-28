# PastureStack Orchestration Engine

Orchestration Engine provides the metadata, process, API, scheduling, storage, networking, and lifecycle coordination layer for the preserved control platform.

PastureStack is an independent community effort to preserve, audit, and modernize the Rancher 1.6 ecosystem. It is not affiliated with or endorsed by Rancher Labs or SUSE.

**Upstream:** [`rancher/cattle`](https://github.com/rancher/cattle). This GitHub fork preserves upstream history, authorship, dates, tags, licenses, and bundled dependency notices. PastureStack maintenance is consolidated into one commit after the preserved upstream boundary.

## Project status

This source tree produces engine version `0.183.287`. It retains the existing Java 25, Ubuntu 26.04, Maven, Liquibase, MariaDB/MySQL, WebSocket, dependency, concurrency, and runtime-hardening work from the maintained compatibility line. Release builds consume the exact `5.7.3-pasturestack.4` runtime JAR published by [`distributed-cache-runtime`](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.3-pasturestack.4), verify its pinned SHA-256 and embedded dependency metadata, and install it only into the build-local Maven repository. Provenance and scope are documented in [`third-party/HAZELCAST.md`](third-party/HAZELCAST.md).

WebAuthn verification uses WebAuthn4J's maintained `tools.jackson` 3.2
dependency line. The existing platform JSON surface remains on
`com.fasterxml.jackson` 2.22. Packaging gates admit only the reviewed,
version-pinned pair and verify that their class namespaces are disjoint.

Host compatibility is evidence-based. The default policy recognizes the preserved legacy ranges plus only the modern Docker Engine releases that have completed the PastureStack host matrix: `24.0.9`, `29.4.1`, and `29.7.2`. It does not imply support for untested intervening releases.

The build and Dapper images compile the Docker `29.7.2` CLI from the pinned official tag commit with Go `1.27.0`; they do not import Docker's precompiled Go `1.26.5` binary. The source archive SHA-256 and Go builder image digest are enforced by the source gate and the resulting images are scanned before release.

Container and service port changes expose a read-only `portpreflight` project action. The action evaluates persisted workload ownership, eligible-host capacity, requested scheduling constraints, rolling-upgrade overlap, and live Node Agent socket observations before a change is saved. Primary and sidekick bindings retain their own network modes while sharing one physical-host collision check. Managed-network published ports are unique across the environment even when a workload targets one host; bridge and host-network checks remain scoped to an explicitly requested host, and host networking checks the effective container port rather than a misleading published-port remap. Running owners block the applicable scope, stopped owners remain visible as warnings, and incomplete live inspection is reported as unknown rather than available. During a start-first upgrade, unchanged bindings reserve their current hosts without being reported as self-conflicts; changed bindings are checked as new requests, and runtime probes ignore only the exact containers already represented by those persisted reservations. The allocator and final create/upgrade validation repeat the authoritative check so the browser result is never the only enforcement boundary. Project authorization explicitly exposes the action's nested input and read-only result schemas; regression tests load the shipped authorization overlays and verify the network-scope, upgrade-capacity, self-ownership, and runtime-probe contracts.

Container and service volume changes expose a read-only `volumepreflight` project action. The Engine registers the input, result, and issue types in the core add-on TypeSet, and project authorization explicitly preserves their create or read-only permissions so the browser can perform the same storage-driver, path, access-mode, eligible-host, and `pasturestack-nfs` coverage checks enforced again during create and upgrade. Packaging regression tests verify both the real TypeSet registration and the shipped user and project authorization overlays; the release-artifact gate checks the same registration inside the packaged application.

Product-facing names use PastureStack terminology. Established Java packages, Maven coordinates, database identifiers, settings, API schemas, event names, Docker labels, and executable aliases remain where changing them would break compatible installations.

Authentication keeps a platform account as the stable authorization
principal. Local credentials and external identities are explicit login links;
provider changes do not recreate accounts. External OpenID Connect identities
are matched by exact issuer and subject rather than username or email.
System-administrator workflows support verified identity reassignment,
permission transfer, disabled-account restoration, safe provider switching,
and MFA-protected local recovery.

Interactive MFA supports RFC 6238 six-digit TOTP, WebAuthn passkeys including
Windows Hello, phones, and hardware security keys, hashed single-use recovery
codes, and verified email account recovery. Email is not treated as an MFA
factor. Passkey limits, enforcement, WebAuthn relying-party settings, and
encrypted SMTP configuration are administrator-controlled.
Enrollment, recovery-code generation, and recovery-address verification
require the account holder's authenticated session. Administrators can inspect
and revoke another account's security material, but cannot create factors or
retrieve recovery codes on that account holder's behalf.

## Build and test

Run the complete JDK 25 package gate before publishing an engine artifact or Server image:

```sh
bash scripts/check-cattle-jdk25-full-package
```

The gate performs dependency-hygiene checks, builds every Maven module with JDK 25, rejects retired packaged libraries, verifies class-file major version `69`, and starts the standalone application against an isolated H2 database.

To create the complete release archive after the gate passes:

```sh
ENGINE_VERSION=0.183.287 bash scripts/build --release
bash scripts/check-release-artifact dist/artifacts/cattle.jar
```

Release packaging uses the exact Git revision and commit timestamp as reproducible build inputs. The selector accepts only a complete web application containing the launcher, Runtime resources, authentication logic, and `WEB-INF/web.xml`.

The build environment is reproducible by construction: Ubuntu `26.04` and all test-service images are digest-pinned, direct Ubuntu packages and base-image security updates are locked to snapshot `20260826T000000Z` in [`ubuntu-apt.lock`](ubuntu-apt.lock), Temurin `25.0.4+7` and Maven `3.9.16` downloads are checksum-verified, and the Maven wrapper distribution is checksum-pinned. The manual GitHub security gate builds and tests the candidate, produces CycloneDX and dependency evidence, scans both the release artifact and Dapper image, and publishes evidence before enforcing zero applicable Critical or High findings. It does not publish a release, container image, catalog entry, or deployment.

Database-backed and full-stack suites require isolated MariaDB/MySQL and companion-service fixtures. See [COMPATIBILITY.md](COMPATIBILITY.md), [SECURITY.md](SECURITY.md), and [ORIGIN.md](ORIGIN.md).

## Language support

User-facing translations are supplied by the PastureStack web console. API field names, persisted values, event names, identifiers, and remote error payloads are compatibility data and are not translated.

## License and attribution

The inherited project remains licensed under [Apache License 2.0](LICENSE). Copyright and attribution for inherited work and bundled dependencies remain with their respective authors and contributors. PastureStack contributors claim authorship only for their own changes.
