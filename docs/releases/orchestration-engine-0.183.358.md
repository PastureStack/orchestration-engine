# Orchestration Engine 0.183.358

## Behavior

A service reconcile can retire its managed service/instance association before
its asynchronous stop/remove executes. The original scoped Key then loses the
ancestry needed for authorization, leaving a retired instance stopping. The
normal service scheduling owner now captures a closed, engine-verified edge.
Later cleanup reloads the exact managed relationship, live project/owner and
current Key state, expiry, revision and explicit child DENY. Ordinary ancestry
resolution is unchanged; a historical row or a caller payload is never a grant.

The same owner chain covers scale-down, error-instance restart and Service/Stack
removal while retaining each original update/delete operation. Only the private
OS volume of that instance may inherit destructive cleanup. Named/file volumes
keep their normal live/Mount-proof deactivation path, with shared/cross-instance
restrictions; they never inherit REMOVE. Already-cancelled old jobs do not gain
retroactive proof or an automatic replay.

Typed name searches now pass a literal substring condition to the ordinary
native manager before its 51-row fetch. In-memory owners filter authorized
projections before taking a 50-row page. Native permission and parent/cursor
conditions remain; wildcard characters are data. This avoids scanning unrelated
old rows through repeated empty cursor pages. Ordinary APIs are not modified.

## Validation and boundary

The original 357 implementation reproduced the managed retirement failure.
Fourteen unique focused lifecycle cases passed, including the named-volume
regression found during independent review. Three new real H2/native JooqUtils
search cases passed with actual module test compilation. Local JDK is
25.0.4.101 and Maven 3.9.16; required component CI qualifies its own toolchain.
No runtime dependency is updated. Previous unaffected evidence is retained.

Required component CI/CodeQL passed: 374 suites, 3,631 tests, zero failures/errors
and one existing conditional skip. Eleven public assets match original CI bytes;
JAR SHA256 `46ad548c6e284a7758eb7f65c85fb5878e2ebdc861ef5c396a00a6510709b733`.
Producer `1da1f4c37fe43b8c387045c263d286f7db01c574` and signed release/tag
source `50569f39f241216d38661f49828dec0d925bb605` share the tested tree.
Assembled Server publication and fresh 8080 lifecycle/weak-model acceptance
remain separate pending gates. This is not complete
Catalog, advanced-form or frontend coverage. The frozen Catalog/Archive work is
separate. Roll back through the assembled Server procedure, preserve data and
plan receipts, and never blindly replay an uncertain or revoked operation.
