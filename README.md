# PastureStack Orchestration Engine

Orchestration Engine implements the platform API, authorization, metadata,
scheduling, storage, networking, and resource lifecycle.

PastureStack is an independent community effort to preserve and modernize the
Rancher 1.6 ecosystem. It is not affiliated with Rancher Labs or SUSE.
This fork of [`rancher/cattle`](https://github.com/rancher/cattle) preserves
upstream history, authorship, licenses, and dependency notices.

## Current build

Engine `0.183.334` is the component candidate for Server `v1.6.519`.
Both API roots support full, closed or custom Key access, optional expiry and
per-Key durable audit. Full and untouched legacy Keys keep their existing scope;
all policies remain bounded by the owner's live RBAC, and explicit denies win.
A broader policy or longer validity uses the existing actor-bound, single-use MFA
confirmation. Secret delivery is creation-only; audit never records credentials
or request payloads. HTTP acceptance is separate from job or stream completion.
See the [API Key policy and audit contract](docs/api-key-policy.md) for ancestry,
revision, error and delegated-stream details.

The component artifact alone is not proof of complete platform acceptance.
Use the [current Server release](https://github.com/PastureStack/server#current-release)
for the published platform version. Source identities, checksums and acceptance
evidence belong in [release notes](docs/releases), not installation commands.

Use the Server image to deploy the complete platform; `cattle.jar` is a component
artifact, not a replacement for its database, authentication, agent, or proxy.
Historical changes are in [release notes](docs/releases).

## Build and validation

The supported baseline uses JDK 25 and Maven, with MariaDB/MySQL and Liquibase
for platform data. Exact toolchain/dependency versions and compatibility limits
are documented in [COMPATIBILITY.md](COMPATIBILITY.md). The build verifies the
pinned [embedded Cache artifact](third-party/HAZELCAST.md) before installation in
its build-local Maven repository.

Before publishing an Engine artifact, run the complete package gate:

```sh
bash scripts/check-cattle-jdk25-full-package
ENGINE_VERSION=0.183.334 bash scripts/build --release
bash scripts/check-release-artifact dist/artifacts/cattle.jar
```

The gate builds the Maven modules, checks dependency hygiene and packaged classes,
and starts the application against isolated H2. Full database/platform lifecycle
validation requires isolated MariaDB/MySQL and companion services. Component
build/startup results do not establish every resource/role combination.

Frozen v1 schemas and v2 overlays are compatibility contracts. A deliberate
frozen-schema migration must use the reviewed source generator and regression
tests documented in [COMPATIBILITY.md](COMPATIBILITY.md); never modify deployed
schemas or infer permissions solely in the browser.

See [SECURITY.md](SECURITY.md) for reporting and security boundaries and
[ORIGIN.md](ORIGIN.md) for provenance.

## Language and licensing

The Web Console supplies user-facing translations. API fields, persisted values,
event names, identifiers, and remote protocol errors are compatibility data and
are not translated.

The inherited project uses [Apache License 2.0](LICENSE). Bundled dependencies
retain their own licenses and attribution.
