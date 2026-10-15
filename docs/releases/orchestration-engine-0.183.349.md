# Orchestration Engine 0.183.349

## Changes

Custom API Keys now recognize a bounded set of existing, persisted-resource
actions: Stack outputs and lifecycle operations, Service continue-upgrade and
create, and Container/Volume allocation and lifecycle operations. The central
registry binds each action to its resource type; a plugin cannot borrow a new
grant solely by reusing an action name. The native environment/Stack and
instance/Container aliases retain their existing meanings.

Queued operations reclassify the original action with the same resource type
before execution. Owner permission, exact resource ancestry, policy revision,
expiry and explicit deny checks remain in effect. Full and legacy Keys do not
gain a new operation-registry restriction. No schema, database, authentication,
firewall or plugin ownership change is introduced.

The reviewed mapping and remaining unsupported multi-target, identity/security
and delegation operations are listed in
[`scripts/api-key-operation-inventory.json`](../../scripts/api-key-operation-inventory.json).
An unsupported operation remains denied for custom Keys; this release does not
claim that every platform action is available to scoped Keys.

## Verification and rollout

The focused local test set passes 150 cases: typed admission, exact-ID and owner
boundaries, denied-handler isolation, request IDs, HTTP dispatch and queued
authorization. Previously passed, unaffected suites are carried forward.
Component publication, packaged Server acceptance and native action results are
separate gates and must be verified before deployment is called complete.

There is no database migration. Rollback must retain current Key policy data,
revocations and expirations; do not restore an older database to roll back code.
