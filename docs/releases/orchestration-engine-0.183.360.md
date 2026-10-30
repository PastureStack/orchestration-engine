# Orchestration Engine 0.183.360

[Published release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.360)
contains the original qualified CI bytes, without a publication rebuild.

## Behavior

API-Key audit events created inside a Core write transaction remain in the
forced durable outbox until the existing independent replayer can commit them
without joining that transaction. A typed read denial must survive the outer
request rollback. This preserves existing metadata, capacity, delivery errors
and at-least-once semantics without introducing nested account-row locks.

Fix project-scoped collection admission through the Catalog response validator
while retaining native SQL scoping and deny checks. Unknown or synthetic list
filters remain rejected. Combined runtime acceptance is pending.

External Services support stopped creation, bounded hostname/IP target updates,
metadata and registered lifecycle changes through native owners. Hostname and IP
modes are mutually exclusive. Endpoint ownership does not borrow Instance grants.
DNS Aliases use a stopped empty creation followed by a separately approved local
target-binding plan. Native ServiceConsumeMap UUID/owner/Stack pins, current Key
revision/roles and explicit DENY checks are revalidated before queued execution.
Advanced/remote links and incoming-consumer/cross-source cleanup remain pending.
The persisted Service kind selects Alias validation and private queued identity
on both base /services and /dnsServices routes. A URI spelling cannot bypass
the closed subset or omit its eight native child-reference pins. Full/Cookie
and ordinary Service behavior remain unchanged.

A bound terminal authorization failure observed before an HTTP acknowledgment is
retained only for the exact plan/operation. Missing or mismatched bindings remain
unknown; a late acknowledgment or success cannot erase a failed result. Partial
effects require inspection and must never be blindly replayed.

Typed audit metadata queries reuse the broker's current row/Key visibility,
time bounds, paging and scan cap. Fixed executable/arguments and private stdin
retain original caller credentials; no serviceFull identity, generic proxy, SQL
reader or raw log payload is introduced. Engine observes actual aggregation and
records ALLOW/DENY/error through the existing canonical audit sink.

The complete operation inventory is re-extracted: one audit-query read POST is
added; only dnsService.setservicelinks changes from unsupported action to update.
Other existing tuple classifications and historical producer identity stay.
Native read/write roles, Full/legacy paths, sessions and default opt-ins remain.

## Published component and rollout boundary

The required [component gate](https://github.com/PastureStack/orchestration-engine/actions/runs/40986782556)
and [CodeQL verification](https://github.com/PastureStack/orchestration-engine/actions/runs/40986782554)
passed. CI executed 3,822 tests in 408 suites with zero failures/errors and one
existing skip. The CodeQL alert gate reported no new alerts and zero annotations.
Original CI artifact `12948190028` and all twelve published assets passed
checksum verification by anonymous readback.

| Immutable coordinate | Value |
|---|---|
| Producer | `72230c8b471312377bae968c0c17d63e8ad97ffb` |
| Merged/tag source | `d6845eedd1df5ae8e69cc075d80b166bc8d26731` |
| Shared tested tree | `29e283abea5eae51eda050865bb4d08e9d27daf3` |
| Signed numeric tag | `v0.183.360` |
| Original cattle.jar SHA-256 | `0ebbd517ee43e113f29cbc14fe542aa7c7900ae28d65d63a12c348b7f0ac9a21` |

Download [cattle.jar](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.360/cattle.jar)
with its [checksum](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.360/cattle.jar.sha256).
Deployment uses the assembled [Server installation](https://github.com/PastureStack/server#quick-start),
not a source SHA or a standalone JAR command. The release includes artifact SBOM,
raw/effective scan, vendor proof, source-revision and unit-test evidence. Passing
the applicable security gate does not mean zero reported CVEs. Provenance is the
original GitHub artifact digest, source revision and same-tree producer binding;
there is no native SLSA attestation claim. Later documentation commits do not
replace that producer or move the tag.

| Qualification | Status |
|---|---|
| Component source, complete required CI and CodeQL | Passed for the exact producer above. Focused cases qualify their named boundaries. |
| Original artifact and public bytes | Passed; no publication rebuild. |
| Matching assembled Server560 | Pending; designated QA still runs published Server559 / Web200. |
| New browser/role/cross-ID/layout and actual weak-model workflows | Pending. Prior unaffected evidence and original failures are retained. |

Use matching Web Console 1.6.201 / bridge 0.2.3, Catalog 0.20.17 and Compose
0.14.40 contracts, including the matching broker audit-query executable. The four
AI opt-ins retain false defaults. This release does not complete all 243 frontend
business groups. No runtime dependency versions change.

Roll back through the assembled Server procedure while preserving the database,
operation receipts, audit spool, volumes and existing origin/JVM configuration.
Do not blindly replay a denied, cancelled, revoked or uncertain operation; inspect
its original receipt and partial effects. Existing release tags remain immutable.
