# Orchestration Engine 0.183.346 - MariaDB ancestry SQL compatibility

## Scope and repair

MariaDB rejected a custom-Key Instance Link query when its optimizer rewrote
the correlated parent Stack EXISTS into a row/IN expression containing a scalar
subquery. The parent lookup now uses a scalar marker bounded by the Stack's
unique primary key, the same owning account and live-parent predicates. Missing
or inaccessible parents still fail closed; global optimizer settings are unchanged.

The shared Container/Link ancestry predicate also replaces the two nullable
Stack DISTINCT FROM comparisons with portable explicit total equality:
both NULL, or both non-NULL and equal. The invalid-parent check negates that
total predicate, so NULL/value disagreement cannot become SQL UNKNOWN and hide
an incoherent managed relationship.

Owner and same-account checks, live parents, persisted source ancestry,
managed Service and exact upgrade relationships remain intact. The destination
Instance is not a Link scope parent. Scope and explicit denials still precede
pagination; expiry, audit, full/legacy behavior and reference authorization are
unchanged. No database migration, role grant or database configuration change
is introduced.

## Qualification boundary

Complete MYSQL runtime-dialect Link and Container SELECTs executed read-only
on MariaDB 11.8.6 using synthetic IDs and no database writes. The five-row
NULL/equal/different truth table and full-query execution are distinct checks;
neither establishes native business authorization.

Three focused methods passed: portable nullable comparison/parent-marker SQL,
source ancestry scope before pagination and deny, and open-default/exact-ID
live same-owner parent guards. Other accepted tests were not replayed.
Immutable artifact publication, assembled-Server native Container/Link
acceptance and deployment retain their separate qualification evidence.

Required normal PR checks, the original release packager and artifact gates
remain mandatory. No unrelated accepted matrix suite is replayed or relabeled.
Use the actual published Server manifest for the Engine/Web pairing and retain
configuration, volumes and matching database backups. Returning to `0.183.345` retains
the known affected custom-Key ancestry SQL compatibility problem. Historical
release notes keep their original evidence and scope.
