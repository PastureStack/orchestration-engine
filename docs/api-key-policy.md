# API Key policy and audit contract

This describes released Engine `0.183.338`. It is an independent component;
published Server `v1.6.521` still packages Engine `0.183.337` with Web Console
`1.6.181`. The new component does not change that deployed contract. Matching
Server integration and live acceptance remain separate from publication and
focused validation; complete UI/API and role/resource acceptance is not claimed.

## Authorization and updates

Both API roots support full, closed or custom policies and optional expiry.
Full and untouched legacy Keys retain the account's existing authorization;
an upgrade does not automatically narrow them. Custom policies can narrow
access by persisted environment, Stack or resource IDs, but never grant more
than the owner's current permissions. Explicit denials take priority.

Project objects are Accounts internally, but their access is determined by the
normal Project membership contract, not equality with the user's Account ID.
The Key resolver and Project resource manager share the existing live AuthDao
check, active-state rules, owner and member-management capabilities. The resolver
runs this check before evaluating custom Key scope, including accepted parent
references and background lifecycle ancestry. Background checks do not create an
HTTP context or object-access grant. Other Account kinds and child resources keep
their own existing authorization; Project membership alone cannot authorize them.

Policy-bearing writes are checked after authentication and before schema
sanitization. Forbidden owner or credential fields are rejected before they
can be silently discarded into an accepted policy change. Ordinary legacy
writes without policy input retain their existing schema behavior.
The sensitive `secretValues` and `dbdump` links are classified as `export`,
not `read`. Full and untouched legacy Keys retain their existing live RBAC;
custom policies reject unregistered actions rather than inferring `create`
from POST. Ordinary resource relationship links remain read operations.

In `0.183.338`, custom resource grants cannot create credentials, change account
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
or response state, never an untrusted request body. Server `v1.6.521` also includes
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

For the current audit identity change, see the
[0.183.337 release notes](releases/v0.183.337.md). The
[0.183.335 integration contract](releases/orchestration-engine-0.183.335.md)
records the Project membership, lifecycle and rollback boundaries introduced
with that release. Published companion versions are
[Server v1.6.521](https://github.com/PastureStack/server/releases/tag/v1.6.521)
and [Web Console 1.6.181](https://github.com/PastureStack/web-console/releases/tag/1.6.181).
Use the complete compatible Server image; the component alone does not prove
every platform role/resource combination.
