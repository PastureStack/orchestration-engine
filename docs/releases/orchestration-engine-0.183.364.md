# Engine 0.183.364 candidate

Not yet published. Required original CI/CodeQL, artifact publication and matching
Server/native business acceptance remain pending. The current published Engine
is 0.183.363; do not deploy an unsigned working tree or replace runtime files.

## Corrected native workflows

A CUSTOM API Key authorized to remove its standalone container could stop at
private Image cleanup because that bookkeeping row has no account ACL. A new
resolver admits only the native DELETE chain, its unique device-0 OS volume,
and the exact private Image/cache reference graph. Verified native frames,
metadata, persisted UUIDs, current actor/role/Key revision/expiry, the root grant
and every explicit dependency DENY remain required. Shared images, data/named
volumes, foreign references, managed-Service instances and public Image/Volume/
Pool access receive no new grant. Full/legacy and existing Service retirement
paths are retained.

Changing an External Service from hostname to IP persisted the new target but
left the old hostname map in reconciliation. Its null desired hostname caused
an exception before stale-map cleanup. Null or a different exact hostname now
marks that old unit for the existing cleanup path. Substring matches no longer
keep an obsolete target. Target replacement, missing-map, stop/remove and Alias
planner boundaries have focused regression coverage.

## Evidence and acceptance boundary

Against the original qualified 363 JAR, the standalone positive case reproduced
OwnerPermissionDenied; six focused methods pass with the two corrected classes.
Eight External lifecycle methods reproduced three original failures, including
the same null-pointer call chain; all eight pass with the corrected native class.
The five complete changed Java files pass the repository Checkstyle rules. These
local checks do not replace required CI or native end-to-end acceptance.

The regenerated operation contract binds 432 current source inputs, including
the native External unit explicitly, and preserves all 1,396 HTTP tuples and
operation classifications. The derived cleanup authorization has a substantive
behavior correction; unchanged HTTP classification is not a zero authorization-
semantic-delta claim. Historical producer355 and both previous-review chains
are retained. The existing required executed-case gate is extended by the six
and eight exact new methods without removing any existing requirement.

MariaDB JDBC3.5.11 and other dependency/security decisions remain unchanged.
The four AI opt-ins remain independently default-off. Existing data, native role
ceilings, Key scope, human confirmation, session ownership and module boundaries
are preserved. Matching Server Core and External/Alias workflows, affected
role/Key/cross-ID cases and cleanup must pass before their matrix entries pass.
The original failed reports remain; this is not complete frontend acceptance.

## Upgrade and recovery

Consume a newly published matching Server patch image through its normal
release. Preserve volumes, operator settings, origins, restart/AppArmor policy,
and a compatible prior image/database backup. A downgrade does not undo writes.
Do not replay an uncertain old operation or replace its revoked Key identity.
Any cleanup of old owned test resources is a separate authorized operation.
