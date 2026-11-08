# Orchestration Engine 0.183.388 candidate

This candidate adds bounded Host activation and deactivation through the existing
query, preview, human confirmation and native execution flow. It does not evacuate
workloads, stop Docker, or change Agent identity.

Cookie-origin Host start/stop, ordinary Service start/stop and existing Service
finish-upgrade, rollback and cancel-upgrade completion now require a durable
witness of the exact native root process inserted for that approved plan. Another
request reaching the same resource state cannot supply that evidence. Service
readiness, prior-instance stopping and retirement/attachment cleanup remain
required in addition to the own-process outcome.

The framework insertion SPI permits a private observer to record correlation;
it grants no authorization and carries no token, claims or caller-supplied marker.
The observer uses the existing transaction manager to bind the inserted process
before scheduling. Its request scope is restored on all exits. Ordinary native
calls and native child processes retain their existing behavior without this
approved context. Current role, Key revision/revocation, UUID/account/CAS, human
approval and cancellation controls remain. Full and legacy clients require no new
header, dependency or schema change.

The original 459 required test identities are retained. New owning methods and
three affected existing methods are enumerated in the normal release manifest;
normal original CI must determine their actual execution results. Local focused
results are development evidence, not component or native qualification.

Normal CI, compiled classes, immutable publication and matching Server native
acceptance remain pending. Host acceptance must use an owned or safely borrowed
target with bounded expected transition and exact restoration; it must verify
workload/scheduling impact without weakening global Host or storage protection.
No data migration is introduced. Deploy through a matching qualified Server
assembly and preserve its configuration, volumes and rollback image.
