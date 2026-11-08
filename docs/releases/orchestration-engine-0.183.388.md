# Orchestration Engine 0.183.388

This release adds bounded Host activation and deactivation through the existing
query, preview, human confirmation and native execution flow. It does not evacuate
workloads, stop Docker, or change Agent identity.

Cookie-origin Host, ordinary Service, registry, load-balancer, external/DNS
service, Container and Stack lifecycle completion requires durable evidence of
the exact native processes belonging to the approved plan. Restart binds the
actual native stop/start chain; Stack binds the exact child identity set and
explicit stable no-ops, including an empty Stack. Another request cannot supply
this evidence. Every original domain terminal/readiness/retirement predicate
remains required. Stack fanout can partially schedule and is not an atomic or
zero-effect operation. A later tracking failure yields unknown rather than
interrupting an already accepted native chain; native insert/queue errors retain
their existing failure behavior.

The framework insertion SPI permits a private observer to record correlation;
it grants no authorization and carries no token, claims or caller-supplied marker.
The observer uses the existing transaction manager to bind the inserted process
before scheduling. Its request scope is restored on all exits. Ordinary native
calls and native child processes retain their existing behavior without this
approved context. Current role, Key revision/revocation, UUID/account/CAS, human
approval and cancellation controls remain. Full and legacy clients require no new
header, dependency or schema change.

Original CI passed 4,306 tests in 483 suites, with zero failures/errors and
one inherited skip. All 492 required methods and 89 packaged roots passed.
Immutable release 438056242 and original workflow 42450335938 retain exact-source
and cryptographic evidence.

Component CI, compiled proof and immutable publication are qualified by their
actual release records. Matching Server deployment and native acceptance remain
pending or partial and are not established by component results. Host acceptance
must use an owned or safely borrowed
target with bounded expected transition and exact restoration; it must verify
workload/scheduling impact without weakening global Host or storage protection.
No data migration is introduced. Deploy through a matching qualified Server
assembly and preserve its configuration, volumes and rollback image.

## Published identity

Producer: `09b12c0f0e5488fc8d25b3a58a228bec4e0c7da8`. Tested tree:
`b91bb14f79350188f10223451038c4c3145cac8f`. Normal merge/tag:
`c476998eff6af7ab6656db4d9fede5adfb92f7b7`. JAR SHA256:
`2ad513ed2003c10723779cfb85d5b80276f742430a9605ad82e430ac73294bbb`.
Twelve assets passed anonymous readback; immutable release verification covered
thirteen cryptographic subjects. Workflow SLSA was not provided; no SLSA claim is
made. These records do not establish Server deployment or native workflow PASS.
