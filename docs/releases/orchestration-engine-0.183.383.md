# Orchestration Engine 0.183.383 candidate

This source candidate adds ordinary-Service dependency-link replacement and
Registry activation/deactivation to the existing typed, human-reviewed change
workflow. It is not a published component or native acceptance result.

Link previews capture the complete before/after set. Native link writers share
the same lock and transaction boundary, including API changes, selector updates,
upgrade cloning and removal. Apply rechecks the current set, identities, account
role and API Key permission; stale or foreign references cannot borrow authority.

Registry changes delegate only to the same Registry StoragePool's native
activate/deactivate action. Current kind, UUID, schema and Key permissions are
checked again at execution and terminal readback. Private configuration is used
only for server-side consistency checks; credentials are not exposed by tool
results or human-review projections. Removal and credential updates are excluded.

The prior Engine382 load-balancer Agent and legacy process-frame repairs remain.
The candidate retains the original 286 required CI methods and adds 44 existing
regressions, for 330 required methods. Source inventory has 477 inputs; the 1,396
native HTTP operation tuples and native role grants are unchanged. Formal CI,
artifact/schema qualification, immutable publication and matched Server native
role/Key/CAS/cleanup acceptance remain pending.

Use matched Web Console 1.6.223 / optional Bridge 0.2.25 and a qualified Server
assembly. Preserve the previous qualified image, operator configuration and
matching backup for rollback. No data migration is introduced.
