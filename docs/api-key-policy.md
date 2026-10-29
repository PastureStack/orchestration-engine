# API Key policy and audit contract

This describes Engine `0.183.356` source. Use the current Server release and its
component manifest for the actual immutable platform pairing. Component source,
publication and assembled-Server native qualification are separate gates;
typed all-actions C-03 and complete UI/API or role/resource acceptance are not
established by the focused source tests.

## Optional AI bridge purpose

`purpose=ai-bridge` restricts an existing policy to supported discovery and typed
AI endpoints. It retains the policy's Full/Closed/Custom scope and expiry
and never grants more authority than the current owner. It is disabled unless
`api.ai.bridge.enabled=true`; upgrade plans separately require
`api.ai.service.upgrade.enabled=true`. Ordinary General and legacy Keys keep
their existing operation capabilities.

Typed resource plans additionally require `api.ai.resource.change.enabled=true`.
Metadata views project safe fields through the real domain authorization;
resource plans bind the approved target state and exact typed operation. Keys
cannot approve or cancel their own plans. A scope granting a parent's operation
does not create an implicit child-resource grant.

The server reports verified purpose and policy revision in
`X-PastureStack-Key-Purpose` and `X-PastureStack-Key-Revision`. After initial
project discovery, bridge requests must supply
`X-PastureStack-Key-Expected-Revision`. Missing or changed revision fails closed
instead of silently adopting new authority. Changing an AI bridge policy into
a General policy uses the existing stronger-authority MFA confirmation path.

Service description updates require the expected current value and write only
that field under the existing transaction. Upgrade previews produce private,
expiring plans; a human cookie session must confirm the same actor's plan.
Keys cannot confirm or cancel plans or enumerate the human approval inbox.
Applying a Key-bound plan requires the same Key and revision. Queued lifecycle
work rechecks current authority and preserves explicit child DENY rules.

Tool-event reports retain client-report labels and cannot prove that a business
operation succeeded. The [356 release note](releases/orchestration-engine-0.183.356.md)
describes resource plans, migration and the separate native-acceptance boundary.

## Authorization and updates

Operation classification uses the actual HTTP dispatch method and its executed
selector, not an action/link label the handler ignores. PUT/DELETE require
update/delete despite decorated URLs. GET/HEAD preserve the invoked link's
read/export classification, including sensitive exports; an ignored action
query cannot replace it. Registered POST actions keep their existing upgrade
and other operation semantics. This does not convert a narrow grant into read.
Custom-Key queued execution also recomputes the captured method/name operation
with the persisted resource type;
contradictory stored operation metadata fails closed before process work.
Full and legacy Keys retain their normal owner/lifecycle checks, without
acquiring a custom scope. Ingress source results and queued source controls are
distinct evidence, neither establishing native typed all-actions C-03 PASS.

Both API roots support full, closed or custom policies and optional expiry.
Full and untouched legacy Keys retain the account's existing authorization;
an upgrade does not automatically narrow them. Custom policies can narrow
access by persisted environment, Stack or resource IDs, but never grant more
than the owner's current permissions. Explicit denials take priority.

Granting update, delete or a registered action does not implicitly grant read.
The manager can load only the exact requested mutation target using that
operation; ordinary GET, collections and references remain read-scoped. The
server binds this internal lookup to the same request, validated decoded ID,
type and operation. Unrecognized or mismatched lookups fall back to the read
contract, and derived aliases must match the persisted schema record class.

Project objects are Accounts internally, but their access is determined by the
normal Project membership contract, not equality with the user's Account ID.
The Key resolver and Project resource manager share the existing live AuthDao
check, active-state rules, owner and member-management capabilities. The resolver
runs this check before evaluating custom Key scope, including accepted parent
references and background lifecycle ancestry. Background checks do not create an
HTTP context or object-access grant. Other Account kinds and child resources keep
their own existing authorization; Project membership alone cannot authorize them.

The existing native `instanceLink` type derives its Project and Stack scope from
the persisted source Instance, with the same live owner, managed Service and exact
upgrade ancestry checks used for containers. Direct reads and collection SQL agree;
scope and explicit denials are applied before pagination. Neither a client pointer
nor the destination Instance supplies a parent grant. Reading a Link grants no
ordinary read access to either Instance, and a changed destination requires its own
live reference authorization. Missing, removed, foreign or contradictory ancestry
fails closed even with an open default or an exact Link ID.

The Link parent Stack check uses a scalar marker bounded by the Stack's unique
primary key, the same owning account and live-state predicates. Missing or
inaccessible parents remain fail-closed. This avoids MariaDB's correlated EXISTS
optimizer rewrite without changing database-wide optimizer settings.

Shared Container/Link SQL treats nullable Stack equality as a total predicate:
two NULL values agree, a NULL and a value differ, and two values agree only when
equal. Both-non-NULL guards keep negated comparisons from becoming SQL UNKNOWN
and omitting an invalid managed parent. This does not remove any live, owner,
managed, upgrade or incoherence check. SQL rendering, database execution and
assembled-Server native acceptance remain separate qualification gates. Full
MYSQL runtime-dialect Link/Container SELECTs ran read-only on MariaDB 11.8.6
using synthetic IDs; that result does not establish native business grants.

Policy-bearing writes are checked after authentication and before schema
sanitization. Forbidden owner or credential fields are rejected before they
can be silently discarded into an accepted policy change. Ordinary legacy
writes without policy input retain their existing schema behavior.
The sensitive `secretValues` and `dbdump` links are classified as `export`,
not `read`. Full and untouched legacy Keys retain their existing live RBAC;
custom policies reject unregistered actions rather than inferring `create`
from POST. Ordinary resource relationship links remain read operations.

Since `0.183.338`, custom resource grants cannot create credentials, change account
roles or identities, enroll authentication factors, or change authentication/security
settings outside their policy. These security-management writes are rejected with
`ApiKeyRestrictedDelegationUnsupported` and the existing denial audit. Setting
targets use their stored names, including numeric and typed aliases. Unchanged
role/identity fields in a full account representation are removed before persistence
so ordinary metadata edits cannot replay stale authority. Full and untouched legacy
Keys retain their normal owner authorization; this is not a ban on all management
operations. Returning to `0.183.337` restores the known scoped security-management
escape and is not a security-equivalent rollback for custom Keys. See the
[0.183.338 release note](releases/orchestration-engine-0.183.338.md).

Creation scope uses only typed parent fields that the API schema actually accepts;
ignored or read-only payload fields cannot grant access to a different destination.
Service-managed container ancestry uses persisted managed relationships, including
when denormalized parent columns are empty. During a service upgrade, a live,
same-account relationship explicitly marked `upgrade=true, managed=false` retains
that ancestry while the old instance is replaced. Ordinary unmanaged, removed,
foreign or contradictory relationships cannot supply a parent grant. Direct
authorization and collection SQL use the same rule. Internal image creation verifies its
persisted container dependency; it does not grant global image access.
A parent grant cannot override an explicit denial on its lifecycle dependencies.
Background container lifecycle work resolves only an explicit, persisted model
graph (host maps, NICs, ports, links, mounts, volumes and IP mappings). Every
owning account and container/service/Stack relationship is checked again; an
internal dependency is not a public resource permission or a process-name bypass.
Removed, foreign, contradictory or ambiguous ancestry fails closed, while
in-progress removal retains its verified ancestry until the removal completes.
The `finishupgrade` cleanup path may use the exact retired upgrade relationship
after its removal listener has run, but only for the same owned instance and its
OS volume. Private per-instance Image/cache rows require the actual verified
Volume/Image execution chain and each cache pool's allocation history. A queued
volume-map removal carries framework-owned parent/child/process and relationship
pins; retries reload the graph and current Key revision, expiry and owner RBAC.
These proofs cannot authorize a public request, another resource, purge, or a
contradictory/shared dependency; explicit denials still apply to every dependency.
Full and legacy Keys retain their ordinary lifecycle behavior, including the
same narrowly verified private-image cleanup, without requiring a custom policy.
Broadening an existing policy or extending its lifetime requires an actor-bound,
single-use MFA confirmation of the exact change. Policy updates use the stored
revision and existing conflict/error handling; client input cannot replace
ownership, revision or raw credential data. New secrets are delivered only at
creation, not returned by later GET/list/detail responses.
Both API versions advertise the `apiKeyPolicyUpdate` confirmation purpose only
where the existing role can create an MFA operation. The v1 frozen-role adapter
adds this one supported purpose without importing other permissions or changing
the serialized role schemas.

Custom collection scope is applied before SQL pagination. Transparent resource
manager filters retain that guarded capability only when the same authorization
guard is installed on the underlying query. Unknown, synthetic or overridden
collection paths cannot advertise it; the normal owner authorization and filter
chain is still executed. Full and legacy policies do not acquire this restriction.

## Identity and durable audit

Key requests record the authorization decision and actual HTTP, background-job or
delegated stream outcome separately. Audit records exclude credentials and request
payloads. Queued work rechecks current account permissions without relying on an
HTTP request or its temporary object-access whitelist. Agent/proxy integration is
required for delegated execution and logging. Key identity lookup is bound to its
owner, not another browser session's Cookie; existing signed owner sessions retain
their original expiry semantics. Local-account Keys keep their existing live RBAC
without acquiring external-provider restrictions from an unrelated browser session.
Key management through the web UI is linked to the managed Key's account and audit
history, with the operator recorded separately, without recording secret delivery
or MFA inputs.

Governance audit uses the same typed public Key ID before and after request
validation, including failed edits. The identity comes from authoritative request
or response state, never an untrusted request body. Server `v1.6.521` and later include
historical numeric Key IDs in per-Key audit queries within existing RBAC, without
rewriting stored events. This normalization does not change authorization, MFA,
Key persistence or audit ownership.

Container audit targets use the persisted instance ID (`1i` prefix) for decision,
response and terminal records, including agent-only schemas without a `container`
alias. A logical resource type must not create a different ID for the same target.
Audit queries recheck the viewer before bounded filtering and pagination.
ALLOW or an HTTP 200/202 response is not proof of a terminal job/stream outcome.
Audit delivery failures retain the existing `AuditUnavailable` contract, not a
fabricated permission denial.

API Key event subscriptions also record a separate `completion` with target type
`subscribe` and no fabricated resource ID. Client/server close is cancellation;
I/O, processor or setup failure is failure. Only a recognized live authorization
failure is denial. The terminal uses the writer's immutable first close cause,
not client text or a later callback. This observer does not add a grant category
or change Full/legacy optional authorization.

Physical unsubscribe and close remain exactly once. A failed terminal admission
gets at most three metadata-only retries through the existing scheduler. Durable
outbox replay and account-locked persistence retain the first result. Exhausted
storage failure emits a fixed diagnostic; neither it nor abrupt server termination
is presented as durable completion. No request, token or writer is retained by
the retry object.

### Audit storage

The default spool is `data/api-key-audit-outbox` relative to the Engine working
directory. The official Server runs the Engine from `/var/lib/cattle`, so this
default remains in its existing persistent data volume. No new volume or database
migration is required.

Administrators can retain `-Dpasturestack.audit.outbox.dir=<directory>` in their
existing JVM configuration. Absolute paths, relative paths and an intentional
administrator-configured symlink remain supported. The canonical destination is
pinned when the outbox is initialized; retargeting it while the Engine is running
is rejected rather than silently moving audit records.

The directory, its ancestors and storage mounts must be managed by trusted
administrators. The spool and its files must belong to the Engine service account
and must not grant write access to other accounts. New POSIX directories and files
use modes `0700` and `0600`; existing private permissions are not changed. A
Linux service identity comes from the operating system, including an unmapped
numeric container UID; a JVM user-name override does not choose the file owner. A
previously group/world-writable custom spool must have its ownership and write
permissions corrected by its administrator before upgrade. Windows fallback
providers also require private ACLs and trusted ancestors; they do not provide
Linux directory-handle race guarantees.

Linux pins and verifies the opened secure directory handle. Generated direct-child
filenames, no-follow opens, exclusive temporary-file creation and bounded reads
protect lock, pending, event and terminal files. Symlink/hardlink events, unexpected
JSON entries, directory replacement, oversized records and unsafe permissions
fail closed. Existing epoch-UUID and terminal event names remain replayable;
database outages retain durable events and the first terminal result.

Unavailable or unsafe audit storage rejects admission through the existing
`AuditUnavailable` error path; it is never bypassed or reported as successful
logging. Keep the configured spool on persistent storage and include it in the
existing data-volume backup and rollback procedure.

## Delegated streams and compatibility

V1 responses only advertise environment aliases when its schema supports them;
the proxy checks the authenticated backend's audit capability before sending any
Key-authorized work. An older backend fails without executing it; a dual-signed,
host-bound receipt records that handshake failure or a ticket used on the wrong
stream route, not a successful execution. These receipts cannot select another
identity, target, arbitrary result or failure code.

An expired, signed Key-traced ticket and authenticated host proof may be verified
only to record a fixed audit denial, never to authorize execution. Each handshake
attempt has its own receipt. After durable acceptance, the rejected handshake
returns `ApiKeyExpired` (401) and records `DENY`/`FAILED`, phase `handshake`.
Missing proof or durable acceptance returns `AuditUnavailable` (503); no backend
or persistent session is started, and no durable receipt is falsely claimed.

For the original audit identity change, see the
[0.183.337 release notes](releases/v0.183.337.md). The
[0.183.335 integration contract](releases/orchestration-engine-0.183.335.md)
records the Project membership, lifecycle and rollback boundaries introduced
with that release. See the
[current component boundary](releases/orchestration-engine-0.183.348.md)
and [current Server release](https://github.com/PastureStack/server#current-release)
for the published companion versions.
Use the complete compatible Server image; the component alone does not prove
every platform role/resource combination.
