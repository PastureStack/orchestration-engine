# API Key policy and audit contract

This is the Engine `0.183.334` contract for the Server `v1.6.519` source
candidate. Component/source validation is separate from platform acceptance
and publication.

## Authorization and updates

Both API roots support full, closed or custom policies and optional expiry.
Full and untouched legacy Keys retain the account's existing authorization;
an upgrade does not automatically narrow them. Custom policies can narrow
access by persisted environment, Stack or resource IDs, but never grant more
than the owner's current permissions. Explicit denials take priority.

Policy-bearing writes are checked after authentication and before schema
sanitization. Forbidden owner or credential fields are rejected before they
can be silently discarded into an accepted policy change. Ordinary legacy
writes without policy input retain their existing schema behavior.
The sensitive `secretValues` and `dbdump` links are classified as `export`,
not `read`. Full and untouched legacy Keys retain their existing live RBAC;
custom policies reject unregistered actions rather than inferring `create`
from POST. Ordinary resource relationship links remain read operations.

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
Broadening an existing policy or extending its lifetime requires an actor-bound,
single-use MFA confirmation of the exact change. Policy updates use the stored
revision and existing conflict/error handling; client input cannot replace
ownership, revision or raw credential data. New secrets are delivered only at
creation, not returned by later GET/list/detail responses.

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

For matching producer versions, lifecycle and rollback boundaries, see the
[0.183.334 integration contract](releases/orchestration-engine-0.183.334.md).
Use the complete compatible Server image; the component alone does not prove
every platform role/resource combination.
