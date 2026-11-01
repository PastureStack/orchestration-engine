# Engine 0.183.364

The original component passed required CI/CodeQL and artifact qualification.
All twelve assets passed anonymous SHA256 readback at the
[published release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.364).
Matching Server/native business acceptance remains pending. Consume the qualified component through its matching Server release; do not
deploy an unsigned working tree or replace runtime files.

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
The five complete changed Java files pass the repository Checkstyle rules.
Original required component CI/CodeQL also passed, as recorded below; matching
native end-to-end acceptance remains pending.

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

## Qualified original CI artifact

Original [Security run 41283488278](https://github.com/PastureStack/orchestration-engine/actions/runs/41283488278)
latest successful attempt 1,
[custom CodeQL run 41283488281](https://github.com/PastureStack/orchestration-engine/actions/runs/41283488281) and
[organization CodeQL run 41283492244](https://github.com/PastureStack/orchestration-engine/actions/runs/41283492244) passed.
All 7 observed PR checks passed, with no new CodeQL alerts.

Producer `e780d1613ab2a14112ba9e14fa55754b4e16ed0b`; merge/tag target `04f5db394efd1ad9bc39b0193be94ac8b7746fd6` (same tree verified);
tested tree `5c73690c54218d3537a06a641a0e38bb944a4689`. The normal same-tree merge is verified.
The qualified files are the original CI bytes; no publication rebuild is performed.

The original cattle.jar SHA256 is `279ae58c5ec0189df09e57fb9a9014b633a5323c884d49e63036046c6f8b15bb` (88235150 bytes).
Original CI artifact ID 13074596446; ZIP SHA256 `fd65c3e03661b91773cacbb2e44b32530c7e7bf81f75162deb6e97584c579504`.
Its actual structured report contains 3872 tests in 416 suites,
0 failures, 0 errors and 1 skip(s).
All six new `StandaloneImageCleanupTest` methods and eight new
`ExternalDeploymentUnitInstanceLifecycleTest` methods passed. Seven inherited
`AiExternalServiceReviewChangeTest` methods and eight inherited
`ServiceIndexCleanupAuthorizationTest` methods also passed in the same report;
these 15 inherited methods are not counted as new Engine364 tests.

The three original packaged changed classes retain their reviewed native cleanup
and target-membership instructions. All five packaged frozen role-schema hashes
match published Engine363. All 89 changed POMs and four build scripts are version-only
updates from Engine363; runtime dependency coordinates remain unchanged.
The packaged MariaDB JDBC3.5.11 JAR SHA256 is `42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4`,
matching the official artifact. SBOM and scanner package inventory identify 3.5.11;
3.5.10 is absent. CVE-2026-119210, CVE-2026-119276, CVE-2026-119281 and
CVE-2026-119280 are absent from current product and raw/effective Dapper reports,
including ignored entries. The required original scan gate passed without an exception.

All 1,396 reviewed HTTP tuples plus the source/unknown-action case passed
(1397 projection rows). The 432 bound source
inputs have fingerprint `169fe350bed9358c2d9cbdac24f75c8636258325f2a92305507ad06e79c993e1`.
HTTP tuples and classifications remain unchanged; derived private-image cleanup
authorization has a substantive correction and is not a zero authorization-semantic-delta claim.
Historical producer355 and both complete previous-review chains remain preserved.

The original archive, packaged modules/classes, role schemas, CycloneDX SBOM,
raw/effective scanner evidence and pip-vendor proof remain bound to this producer.
This is GitHub original artifact/source provenance; no native SLSA attestation is claimed.

Matching assembled Server Core and External/Alias workflows, native private-image
cleanup, affected role/Key/cross-ID cases and designated business workflows remain
pending. These component results do not establish runtime or complete frontend acceptance.
