# Orchestration Engine 0.183.360 candidate

Not yet published or runtime accepted.

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

Focused source cases qualify only their exact boundaries. Component CI/CodeQL,
original artifact verification, combined Server, browser/role/cross-ID layouts
and actual weak-model workflows remain pending. No runtime dependency changes.
