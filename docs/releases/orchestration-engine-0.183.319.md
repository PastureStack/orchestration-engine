# Orchestration Engine 0.183.319

This patch makes shared Default membership reconciliation atomic. The Engine
rechecks all authenticated direct and group identities and, only when none has
an active project membership, creates the stable account membership while
holding the existing project lock. A concurrent administrator update therefore
cannot be weakened by a later baseline `member` grant.

The release preserves the explicit `shared`, `personal`, and `none` provisioning
modes and the legacy `project.create.default=false` switch. Invalid modes fail
closed. A deterministic barrier test repeats the conflicting update order 100
times and verifies that no duplicate or role-escalating membership appears.

The runtime matrix must prove owner, member, restricted, read-only, and
no-access behavior with two accounts per role, direct-user and group
membership, exact collection sets, and direct-ID create/read/update/delete
checks on both v1 and v2 API surfaces before a consuming Server release is
published.
