# PastureStack Orchestration Engine

Orchestration Engine implements the platform API, authorization, metadata,
scheduling, storage, networking, and resource lifecycle.

PastureStack is an independent community effort to preserve and modernize the
Rancher 1.6 ecosystem. It is not affiliated with Rancher Labs or SUSE.
This fork of [`rancher/cattle`](https://github.com/rancher/cattle) preserves
upstream history, authorship, licenses, and dependency notices.

## Current source: Engine 0.183.360 candidate

Use the [Server installation guide](https://github.com/PastureStack/server#quick-start)
for deployment. The latest published artifact is still
[0.183.359](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.359);
this source candidate has not been published or accepted on a running Server.

This candidate adds bounded External Service and DNS Alias plans through native
owners, plus typed audit metadata queries through the installed broker owner.
Aliases are created stopped and empty; selecting authorized service targets is a
separate reviewed change. Audit payloads and credentials remain outside tool
results. Live actor, role, Key revision, explicit DENY and exact resource identity
checks remain in force. Normal Full and legacy API behavior is preserved.

The matching clients are Web Console 1.6.201 / bridge 0.2.3. Existing Catalog,
Archive and session protections remain. Required CI/CodeQL, immutable artifact
verification, assembled Server and browser/role/weak-model acceptance are pending.
See the [candidate release note](docs/releases/orchestration-engine-0.183.360.md)
for supported operations and explicit remaining gaps. This is not completion of
all 243 frontend business groups.

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

Enable the flags for the selected AI workflow by appending properties to the
existing JVM options without replacing its other arguments:

```text
-Dapi.ai.bridge.enabled=true
-Dapi.ai.service.upgrade.enabled=true
-Dapi.ai.resource.change.enabled=true
-Dapi.ai.catalog.launch.enabled=true
```

The bridge, upgrade-plan, resource-change and Catalog-launch flags each default
to false. Enabling one does not enable the others.
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
[359 release note](docs/releases/orchestration-engine-0.183.359.md);
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
ENGINE_VERSION=0.183.359 bash scripts/build --release
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

## Optional AI service image upgrade flow

`api.ai.service.upgrade.enabled=false` is the default. Enabling it opts in to
the `aiServiceUpgradePlans` API in the existing project-scoped API context.
A Cookie actor can use ordinary authenticated collaboration. An independent
stdio client can instead preview and apply with its selected API Key, bounded
by the owner's live project permissions and Key policy. Key-created plans bind
the credential identity and policy revision; that same Key must apply them.
Using a browser's shared Cookie alone does not establish strict credential
isolation. Ordinary UI/API authorization remains unchanged.

* `POST /v2-beta/projects/{projectId}/aiServiceUpgradePlans` with `serviceId` and
  `image` creates a five-minute actor/project-bound preview and, for Key callers,
  a credential/revision binding. It does not upgrade a service.
* The confirmation UI separately calls `POST .../{planId}?action=confirm` with an
  empty body. The same owner's human session confirms through the console;
  API Keys cannot confirm, and a bound Key's current scope, expiry and revision
  are rechecked. Confirmation is not a model tool and no `confirmed` input is
  accepted.
* `POST .../{planId}?action=apply` with an empty body requires that approval,
  current write permission and the unchanged server snapshot. It invokes the
  existing service upgrade action and chains its normal finish-upgrade process.
* `GET .../{planId}` reads the same durable operation under current authorization;
  Key callers must match its credential and revision. `accepted` and `running`
  are not success. Success requires the finish process, active service, requested
  image, retained primary/secondary settings, healthy matching instances and
  retired-instance attachment cleanup.
* The human inbox can list up to twenty own pending plans in the current
  project. Key callers cannot list the inbox, confirm or cancel. Human `cancel`
  can cancel a pending or approved-but-unsubmitted preview before dispatch;
  it does not undo an accepted upgrade.

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

`POST /v2-beta/projects/{projectId}/aiToolCallEvents` accepts only the twelve
command names in [ClientToolAuditEvent](code/framework/api/src/main/java/io/cattle/platform/api/auth/ClientToolAuditEvent.java)
(or `unknown`), `start`/`result`, enumerated status/reason, and an optional
opaque correlation ID. It executes no command. Active session or API Key actors
can report their own bounded metadata under the applicable role and Key checks;
a readonly member's report grants no resource mutation. The returned
`requestId` and `accepted:true` acknowledge durable outbox storage only; the
reported channel, phase and result remain explicitly `client_reported`, never
proof of authorization or business success. A start requires `pending`; a result
requires the start ACK ID as a client-reported correlation. Optional
`X-AI-Tool-Request-Id` on a plan API call links this client report to the separate
authoritative plan audit without granting any permission. No tool arguments,
resource references, launch configuration or credentials are accepted here.
