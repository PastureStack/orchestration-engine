# PastureStack Orchestration Engine

Orchestration Engine implements the platform API, authorization, metadata,
scheduling, storage, networking, and resource lifecycle.

PastureStack is an independent community effort to preserve and modernize the
Rancher 1.6 ecosystem. It is not affiliated with Rancher Labs or SUSE.
This fork of [`rancher/cattle`](https://github.com/rancher/cattle) preserves
upstream history, authorship, licenses, and dependency notices.

## Release candidate: Engine 0.183.350

Engine [0.183.350](docs/releases/orchestration-engine-0.183.350.md) is the workload
archiving candidate planned for Server `v1.6.537` with Web Console `1.6.190`.
Component publication, packaged Server acceptance and deployment are pending;
this candidate does not replace an existing published artifact.

Stack and Service resources expose an `archived` boolean on v1 and v2-beta.
Missing values on older resources mean false. Send a separate PUT containing
`{"archived":true}` to archive a completely stopped workload, or false to restore
it. This stores display metadata only: it does not stop services, recreate
containers, or delete mounts and volumes. Back up bind-mount directories and
volume data; deleting their source or moving hosts can still lose that data.

A Service must be inactive and every related instance, including sidekicks and
upgrade instances, must be stopped. A Stack additionally requires all its
services to meet that condition; an empty stable Stack can be archived. Active
services with scale zero and transitioning or uncertain states are rejected.
Selector references are checked for stopped eligibility, but do not transfer
lifecycle ownership or prevent an independent container from starting later.
System and infrastructure workloads cannot be archived. Restore an archived
Service and its parent Stack before starting it or adding/moving children.
Restoring a parent leaves independently archived children archived.

Archive updates use existing resource-update permissions and API Key scopes;
readonly remains read-only. Create-with-archive and mixed archive/configuration
changes are rejected. Per-Stack locks serialize archive writes with lifecycle
state transitions. Stop and delete keep their existing behavior.

## Retained platform behavior

The Engine supports full, closed and custom API Key policies, expiry and per-Key audit on
both API roots. A Key is always bounded by its owner's live permissions;
explicit denials take precedence. Expanding access requires an actor-bound,
single-use MFA confirmation. Secrets are delivered only when a Key is created.

The shared Default environment automatically selects the configured public
project template. The packaged default, `PastureStack Native`, matches the
Catalog's `project.yml`. Catalog reload also repairs an unbound Default from an
already installed template. Existing bindings, explicit custom settings and
other environments are preserved. System-stack definitions precede host
registration; their services activate when the first host becomes active.

Operation-specific Keys can load the exact authorized mutation, registered
action or sensitive named-link operand without an extra read grant. Ordinary
GETs, collections and input references still require read access. Operation
classification follows the actual HTTP handler; ignored URL selectors cannot
mask a mutation. A bounded registry also binds additional lifecycle actions to
their persisted resource type, including Stack outputs and Service continue-upgrade.
Unsupported multi-target and security-management actions remain denied to scoped
Keys; see [the action inventory](scripts/api-key-operation-inventory.json).
Queued custom-Key work rechecks its original operation, resource type and
current authorization before execution. Full and legacy behavior is preserved.

Native Instance Link reads and collections use the persisted source Instance's
owned Project and Stack ancestry. Reading a Link does not grant read access to
either Instance; changing its destination still requires independent reference
authorization. Unknown types and incoherent or foreign ancestry fail closed.

Nullable Stack references retain the same owner and live-parent checks for
Container and Instance Link reads.

The Engine also provides typed environment provisioning, dedicated agent
registration-token authentication and private, configured-connection HA dumps.
API Key subscriptions retain secret-free terminal metadata
for client/server close, I/O failure and live authorization failure. Terminal
outbox retries keep the first result; WebSocket client close text is not logged.
Full and legacy subscription authorization remains unchanged.
For the resource, expiry, audit and delegated-stream boundaries, see the
[API Key contract](docs/api-key-policy.md) and
[compatibility contract](COMPATIBILITY.md).

Deploy the [current Server release](https://github.com/PastureStack/server#current-release)
for the complete platform; `cattle.jar` alone is not a Server installation or a
runtime replacement. Server `v1.6.537` is the planned assembly for this candidate;
its publication and deployment must be verified separately. Acceptance is limited
to the documented affected paths, not every resource and role combination.
Versioned evidence and upgrade boundaries are in the
[350 release note](docs/releases/orchestration-engine-0.183.350.md);
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
ENGINE_VERSION=0.183.350 bash scripts/build --release
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
