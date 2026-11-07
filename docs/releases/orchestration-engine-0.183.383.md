# Orchestration Engine 0.183.383

This release adds ordinary-Service dependency-link replacement and
Registry activation/deactivation to the existing typed, human-reviewed change
workflow. Its immutable component assets are published; platform-native business
acceptance remains a separate requirement.

Link previews capture the complete before/after set. Native link writers share
the same lock and transaction boundary, including API changes, selector updates,
upgrade cloning and removal. Apply rechecks the current set, identities, account
role and API Key permission; stale or foreign references cannot borrow authority.
Bulk acquisition uses an iterative ownership ledger. Checked unlock failures
retain the same native mutex and fail closed instead of admitting another writer
under a replacement mutex. Successful stale-marker recovery preserves the next
transaction's ownership; legacy healthy lock behavior remains compatible.

Registry changes delegate only to the same Registry StoragePool's native
activate/deactivate action. Current kind, UUID, schema and Key permissions are
checked again at execution and terminal readback. Private configuration is used
only for server-side consistency checks; credentials are not exposed by tool
results or human-review projections. Removal and credential updates are excluded.

The prior Engine382 load-balancer Agent and legacy process-frame repairs remain.
Original CI passed 460 suites / 4,145 tests with zero failures/errors and one
inherited skip. All 330 required methods (286 carried and 44 new), 43 Linux and
13 PIP controls passed. Source inventory has 479 inputs; all 1,396 native HTTP
operation tuples and native role grants are unchanged. Original JAR/SBOM,
compiled class/schema qualification, twelve anonymous assets and thirteen
immutable-release cryptographic subjects passed. Existing no-fix findings remain
registered; this does not claim a new workflow SLSA attestation or native PASS.

The qualified JAR SHA256 is
`c1ce37023c9ad6fa648f198189bf9fe0ca087dbd0e15b2b54caf9cfe30f82f9b`.
The [published metadata](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.383/engine383-release-metadata.json)
separates the tested producer, normal merge/tag target and later documentation.

Use matched Web Console 1.6.223 / optional Bridge 0.2.25 and a qualified Server
assembly. Preserve the previous qualified image, operator configuration and
matching backup for rollback. No data migration is introduced.
