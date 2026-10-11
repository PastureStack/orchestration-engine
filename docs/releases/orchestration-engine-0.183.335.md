# Orchestration Engine 0.183.335

Component candidate for Server v1.6.519; not a claim of completed platform
acceptance or a published Server image.

## Project authority before custom Key evaluation

Engine 0.183.334 evaluated a custom Key before the normal Project resource
manager could grant access to a legitimate environment member. Since a Project
is internally an Account, the generic Account policy rejected a user's different
Project ID. A full Key followed the original manager path and did not have this
failure.

`ProjectAccessService` now owns the same existing AuthDao membership check,
AccountDao active-state check, owner and member-management capabilities.
`ProjectResourceManager` and the Key target resolver use that shared contract.
Capabilities remain idempotent when both run within one request. Every check
reloads live membership; a previous request grant cannot mask a later revocation.

The change covers direct Project IDs, collection filtering, accepted typed parent
references, implicit current-Project creation and verified lifecycle ancestry.
Non-Project Accounts and child resource policies are unchanged. Background work
does not manufacture HTTP context or inherit request-local grants. Key denials,
expiry, policy revisions and existing account permissions remain effective.

## Validation and deployment boundary

Targeted regression tests cover the shared service, the normal manager's ID/UUID/
collection paths, resolver and authorization positive/negative cases, typed
references, fresh revocation, no-HTTP background checks, full/legacy compatibility,
and expiry/delegation boundaries. The formal artifact gate requires the new class
and its explicit Spring bean in the packaged archive.

The compatible Server must pass startup/restart and real v1/v2-beta cookie-free
Basic Key requests after native UI creation before platform acceptance is marked
complete. Allow/deny audit events and exact test-Key cleanup are part of that
runtime gate; unit tests do not replace it.

There is no database or schema migration. Upgrade/rollback uses complete immutable
Server images and retains existing environment variables, volumes and host
network architecture. Never patch deployed classes or overwrite existing tags.
