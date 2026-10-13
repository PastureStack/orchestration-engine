# PastureStack Orchestration Engine

Orchestration Engine implements the platform API, authorization, metadata,
scheduling, storage, networking, and resource lifecycle.

PastureStack is an independent community effort to preserve and modernize the
Rancher 1.6 ecosystem. It is not affiliated with Rancher Labs or SUSE.
This fork of [`rancher/cattle`](https://github.com/rancher/cattle) preserves
upstream history, authorship, licenses, and dependency notices.

## Current build

Engine [0.183.345](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.345)
supports full, closed and custom API Key policies, expiry and per-Key audit on
both API roots. A Key is always bounded by its owner's live permissions;
explicit denials take precedence. Expanding access requires an actor-bound,
single-use MFA confirmation. Secrets are delivered only when a Key is created.

Operation-specific Keys can load the exact authorized mutation, registered
action or sensitive named-link operand without an extra read grant. Ordinary
GETs, collections and input references still require read access. Operation
classification follows the actual HTTP handler; ignored URL selectors cannot
mask a mutation. Queued custom-Key work rechecks its original operation and
current authorization before execution. Full and legacy behavior is preserved.

Native Instance Link reads and collections use the persisted source Instance's
owned Project and Stack ancestry. Reading a Link does not grant read access to
either Instance; changing its destination still requires independent reference
authorization. Unknown types and incoherent or foreign ancestry fail closed.

The Engine also provides typed environment provisioning, dedicated agent
registration-token authentication and private, configured-connection HA dumps.
For the resource, expiry, audit and delegated-stream boundaries, see the
[API Key contract](docs/api-key-policy.md) and
[compatibility contract](COMPATIBILITY.md).

Deploy the [current Server release](https://github.com/PastureStack/server#current-release)
for the complete platform; `cattle.jar` alone is not a Server installation or a
runtime replacement. The Server component manifest identifies its exact
published pairing. Component checks and platform acceptance remain separate.
Versioned evidence and upgrade boundaries are in the
[345 release note](docs/releases/orchestration-engine-0.183.345.md);
older changes remain in [release notes](docs/releases).

## Build and validation

The supported baseline uses JDK 25 and Maven, with MariaDB/MySQL and Liquibase
for platform data. Exact toolchain/dependency versions and compatibility limits
are documented in [COMPATIBILITY.md](COMPATIBILITY.md). The build verifies the
pinned [embedded Cache artifact](third-party/HAZELCAST.md) before installation in
its build-local Maven repository.

Before publishing an Engine artifact, run the complete package gate:

```sh
bash scripts/check-cattle-jdk25-full-package
ENGINE_VERSION=0.183.345 bash scripts/build --release
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
