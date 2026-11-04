# Engine 0.183.367 source candidate

This source candidate extends the existing human-reviewed Service image upgrade.
It is not a published release or a runtime acceptance result.

The build environments also consume Ubuntu's official PCRE2 update
`10.46-1ubuntu0.1` for `libpcre2-8-0` and `libpcre2-posix3`, covering the eight
issues in [USN-8974-1](https://ubuntu.com/security/notices/USN-8974-1).
Both recipes verify archive SHA256, package identity, architecture and installed
version. Other package locks and snapshot dates are retained.

Typed Audit queries also reject original unknown fields, including caller URL,
method and actor pointers, before they can disappear during ordinary schema
sanitization. A scoped request handler retains only a rejection boolean; the
existing typed owner returns422 and records canonical ERROR with no query-runner
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
owner records its original422 denial before any plan or dispatch. Ordinary native
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

Local focused tests, official component CI/artifacts, publication and matching
Server/native role/UI/weak-model acceptance are separate qualification stages.
No runtime or complete frontend coverage is claimed by this source change.
