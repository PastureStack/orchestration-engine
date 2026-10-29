# PastureStack Orchestration Engine

Orchestration Engine implements the platform API, authorization, metadata,
scheduling, storage, networking, and resource lifecycle.

PastureStack is an independent community effort to preserve and modernize the
Rancher 1.6 ecosystem. It is not affiliated with Rancher Labs or SUSE.
This fork of [`rancher/cattle`](https://github.com/rancher/cattle) preserves
upstream history, authorship, licenses, and dependency notices.

## Current source: Engine 0.183.358

Use the [current Server release](https://github.com/PastureStack/server#current-release)
for an assembled platform. `cattle.jar` alone is not a Server installation.
Versioned artifacts and validation evidence belong in the
[release notes](docs/releases), including the [358 source candidate](docs/releases/orchestration-engine-0.183.358.md).

This source candidate preserves scoped service retirement ancestry only through
verified scheduling edges, with live Key/role/explicit-DENY checks. Named/file
volumes retain their normal deactivation path and never inherit destructive
removal authority. Typed name queries filter through the native owner before
pagination. Previous read fixes remain. Qualification and designated runtime
acceptance are pending; this does not complete all frontend WebMCP workflows.

## Optional AI resource workflows

The Engine supplies typed, bounded metadata queries and human-reviewed resource
plans for compatible Web Console and stdio clients. It reuses real resource
managers, current role/membership checks and the owner's live API Key policy.
Ordinary Full and legacy API Key capabilities are retained.

`aiResourceView` returns an authorized scalar projection, not secret values,
private keys, raw process logs or full launch configurations. Queries and opaque
pagination remain subject to the underlying domain manager's authorization.

`aiResourceChangePlan` supports Stack, Service and Container creation, metadata
editing and the explicitly registered lifecycle operations. Creation currently
covers the named basic fields and creates stopped workloads; it does not claim
all advanced UI form fields. A short-lived plan captures the target state and
Key revision. A human cookie session reviews and approves it; a Key cannot
approve or cancel its own plan. Apply claims one durable operation before
dispatch; an uncertain dispatch is never automatically replayed.

Completion observes the deployment module's actual desired units, sidekicks and
global-host eligibility, plus retired instances and attachment cleanup. A
Stack's state alone cannot prove its child services have started or stopped.
Unused pending or approved-but-unsubmitted plans can be cancelled from the
human inbox. Client-reported audit events remain distinct from server-verified
authorization and completion evidence.

All AI backend features are opt-in. Append required properties to the existing
JVM options without replacing its other arguments:

```text
-Dapi.ai.bridge.enabled=true
-Dapi.ai.service.upgrade.enabled=true
-Dapi.ai.resource.change.enabled=true
```

The bridge, upgrade-plan and resource-change flags each default to false.
Other platform operations remain available through their ordinary UI/API;
this release is not complete WebMCP coverage of every frontend function.

## Platform behavior

### Stopped workload archiving

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

### API Keys and environments

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
The durable audit spool defaults to the Server data volume. Administrator-selected
custom directories remain supported with private ownership and link-safe file I/O;
see [audit storage requirements](docs/api-key-policy.md#audit-storage).
For the resource, expiry, audit and delegated-stream boundaries, see the
[API Key contract](docs/api-key-policy.md) and
[compatibility contract](COMPATIBILITY.md).

Deploy the [current Server release](https://github.com/PastureStack/server#current-release)
for the complete platform; `cattle.jar` alone is not a Server installation or a
runtime replacement. Server component coordinates, publication and deployment
evidence are maintained in the
[Server release notes](https://github.com/PastureStack/server/tree/main/docs/releases).
Acceptance is limited to the documented affected paths, not every resource and
role combination.
Versioned evidence and upgrade boundaries are in the
[356 release note](docs/releases/orchestration-engine-0.183.356.md);
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
ENGINE_VERSION=0.183.356 bash scripts/build --release
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

# Optional AI service image upgrade flow

`api.ai.service.upgrade.enabled=false` is the default. Enabling it opts in to
the `aiServiceUpgradePlans` API in the existing project-scoped API context.
This is ordinary authenticated cookie collaboration using current project RBAC;
it is not restricted API-key delegation. Existing UI/API authorization is unchanged.

* `POST /v2-beta/projects/{projectId}/aiServiceUpgradePlans` with `serviceId` and
  `image` creates a five-minute, actor-bound preview. It does not upgrade a service.
* The confirmation UI separately calls `POST .../{planId}?action=confirm` with an
  empty body. Confirmation is not a model tool and no `confirmed` input is accepted.
* `POST .../{planId}?action=apply` with an empty body requires that approval,
  current write permission and the unchanged server snapshot. It invokes the
  existing service upgrade action and chains its normal finish-upgrade process.
* `GET .../{planId}` reads the same durable operation. `accepted` and `running`
  are not success. Success requires the finish process, active service, requested
  image, retained primary/secondary settings and healthy matching instances.
* `cancel` cancels an unaccepted preview only. It does not undo an upgrade.

Plans and receipts live in the dedicated `ai_service_upgrade_plan` table
(migration 127). The full private snapshot is never returned. An atomic claim
prevents a repeated apply from scheduling again. If the server stops between
claim and scheduling or cannot prove the dispatch result, the receipt is
`unknown`; clients must read it instead of starting another upgrade. This is an
at-most-once dispatch boundary, not a distributed exactly-once guarantee. Disabling
the flag prevents new previews, approvals and admissions while retained receipts
remain readable under current actor/project permissions. Durable admission audit
uses the existing outbox and includes only fixed metadata, never environment or
launch configuration payloads.
Each actor can retain at most 20 pending previews. Creating a preview deletes
expired, unaccepted previews; cancellation and a verified terminal result remove
the private snapshot while retaining the receipt metadata.

`POST /v2-beta/projects/{projectId}/aiToolCallEvents` accepts only the six tool
names (or `unknown`), `start`/`result`, enumerated status/reason, and an optional
opaque correlation ID. It executes no command. An active cookie actor, including
a readonly project member, can record their own bounded metadata. The returned
`requestId` and `accepted:true` acknowledge durable outbox storage only; the
reported channel, phase and result remain explicitly `client_reported`, never
proof of authorization or business success. A start requires `pending`; a result
requires the start ACK ID as a client-reported correlation. Optional
`X-AI-Tool-Request-Id` on a plan API call links this client report to the separate
authoritative plan audit without granting any permission. No tool arguments,
resource references, launch configuration or credentials are accepted here.
