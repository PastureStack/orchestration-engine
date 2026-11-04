# Engine 0.183.367

[Engine 0.183.367](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.367)
is published. It extends the existing human-reviewed Service image upgrade.
Original component qualification and published bytes are verified; matching
Server/native runtime acceptance remains pending.

The build environments also consume Ubuntu's official PCRE2 update
`10.46-1ubuntu0.1` for `libpcre2-8-0` and `libpcre2-posix3`, covering the eight
issues in [USN-8974-1](https://ubuntu.com/security/notices/USN-8974-1).
Both recipes verify archive SHA256, package identity, architecture and installed
version. Other package locks and snapshot dates are retained.

Typed Audit queries also reject original unknown fields, including caller URL,
method and actor pointers, before they can disappear during ordinary schema
sanitization. A scoped request handler retains only a rejection boolean; the
existing typed owner returns 422 and records canonical ERROR with no query-runner
execution. It does not retain the raw payload or change generic API filtering.

`POST aiServiceUpgradePlans` retains `serviceId` and `image` and adds optional
top-level `startFirst` (boolean), `batchSize` (integer, minimum 1) and
`intervalMillis` (integer, minimum 100ms). Defaults remain false, 1 and 2000.
Integers must be exactly representable in JSON clients, at most 9007199254740991;
the native Long fields have no additional product maximum. Raw strategies,
launch configurations and client-supplied approval remain outside this input.
The exact typed preview handler checks the original field set and these three
strategy values before ordinary schema coercion. It retains only a sticky
rejection boolean; unknown fields, numeric strings and fractional integers cannot
silently disappear or become different approved values. The existing preview
owner records its original 422 denial before any plan or dispatch. Ordinary native
Service upgrades and plan confirm/apply actions retain their existing behavior.

The server persists and returns a closed `upgradeStrategy` containing all three
effective values. Human review, confirm, apply and operation reads refer to the
same immutable plan. Confirm/apply cannot override those values. Old persisted
plans without this field retain their original defaults.

New plans also bind the complete strategy into a private snapshot operand. Older
Engines compare their classic snapshot and reject the additional operand with
`UpgradePlanStale` before claiming or dispatching. Newer Engines continue to accept
genuine legacy image-only plans. A pending plan created by a newer Engine must be
previewed and approved again after a downgrade; it cannot silently use older defaults.

Preview, confirmation and approved apply invoke the same native upgrade filter's
read-only strategy invariants and port/volume preflight with the chosen start-first
and batch values. In particular, retain-IP Services reject start-first before any
plan is inserted or operation claimed, with the original native validation error.
The native preflight's existing batch normalization remains unchanged. Final
dispatch still uses the authorized native manager and the full preserved
primary/sidekick configuration; no new mutation grant, role bypass or Key
revision exception is added. A rejected preflight cannot claim or dispatch an
operation. An accepted or uncertain operation is not dispatched again.

Catalog-launch, resource-change and upgrade actions also correct a shared lock
ordering fault in native action operand lookup. Lookup runs inside the API write
transaction: Catalog's locking plan read, or resource-change/upgrade receipt
observation writes, could retain a database row lock after releasing the plan's
application lock. A concurrent receipt GET could then hold the application lock
while waiting for that row, leaving the action waiting for the application lock.

Only the exact framework-owned action marker selects an authorized, non-mutating
operand snapshot. Public GETs retain their normal source checks and receipt
observation. The action reloads its plan under the existing application lock and
repeats current owner, role, Key revision, source/snapshot and durable claim checks
before dispatch. Catalog operand snapshots also avoid retaining credential row
locks; final action authorization keeps its existing locking Key recheck. No
timeout, retry, removed lock, database change or new mutation grant is introduced.
Offline barrier tests use the native action path, shipped API transaction advice,
real application locks and JDBC row locking. They do not establish live UI polling
causality or matching Server/native runtime acceptance.

## Published identity and qualification

| Identity | Value |
|---|---|
| Signed producer | `7cdacb4aad8cfbee3db239bb602a6a047da31fcf` |
| Normal merge and release tag commit | `29e185e90d04891670aa9a3d9bd2cc81f09b35d5` |
| Tested and tagged tree | `f1922c7bb997d74ff6e8e9e77b2d6f5f9952a196` |
| Original `cattle.jar` | 88,250,973 bytes; SHA256 `4435aec3508fafeb1b1d88f455b7cef815c026957820b9127a391728130aa86c` |

[Security CI](https://github.com/PastureStack/orchestration-engine/actions/runs/41874126726)
attempt 1, [custom CodeQL](https://github.com/PastureStack/orchestration-engine/actions/runs/41874133256)
and [organization CodeQL](https://github.com/PastureStack/orchestration-engine/actions/runs/41873957028)
passed. The original structured report records 424 suites / 3,940 tests / zero
failures/errors / one inherited skip. The 13 affected classes contain 28
source-new and 76 inherited methods. All 125 required identities passed,
including the prior 97. The source check also retained all 15 original snapshot
transport tests and passed three new bounded transport/integrity cases.

All 1,396 operation tuples and the source/unknown-action row passed. The 447-input
source fingerprint is `66a5ce2ee5f4277993c03de0f72a08dd828ec65e4c447430248c1b093583ab59`;
API tuple and admission classifications remain unchanged. All 16 frozen serialized
schemas retain their exact bytes. Original packaged-byte checks verify the 13
changed production classes, four native JSON schemas, Audit handler order and
reachable strategy snapshot comparison before claim.

The original Dapper dpkg and raw/effective Trivy inventories verify both PCRE2
packages at `10.46-1ubuntu0.1`. This qualifies the CI build environment; it does
not establish the package inventory of a deployed Server. The application Maven
CycloneDX SBOM remains separate from the builder inventory. Original secrets,
artifact/SBOM coverage and zero applicable Critical/High gates passed.

All 12 original assets passed anonymous SHA256 readback. Publication reused the
original CI artifact without rebuilding; no native SLSA attestation is claimed.
Use the [Server installation guide](https://github.com/PastureStack/server#quick-start)
for a complete installation. A component JAR is not a Server installation.

Matching Server/Web Console/Bridge native business, role/Key, UI and weak-model
acceptance remain pending. Publication does not establish runtime acceptance or
complete coverage of all frontend business groups.
