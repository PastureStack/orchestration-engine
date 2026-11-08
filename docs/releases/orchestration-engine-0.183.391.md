# Orchestration Engine 0.183.391 — source candidate

This version is not published. Matching Web Console **1.6.232** / Bridge
**0.2.34**, original CI, compiled qualification, publication and affected native
acceptance are pending. The current published Engine remains 0.183.390.

## Human-reviewed Registry removal

The typed workflow removes an inactive Registry through its existing native
StoragePool DELETE. It accepts empty operation parameters and opaque target
references; it does not expose credential values or add a credential editor.
Preview and confirmation bind the exact Registry identity and complete bounded
non-sensitive credential set. Apply rechecks that set inside the native owner.
New, reassigned, hidden or incomplete child metadata cannot be accepted as the
reviewed set. A changed target requires a fresh preview and human approval.

Credential creation/reparenting and native Registry removal use the same parent
lock through transaction completion. This closes the child-reference race at the
native writer, including ordinary clients. Previously legal Full/legacy writers
and current role/Key operation ceilings remain authoritative. No native grant,
role capability or generic executor is added.

Cookie dispatch attests this plan's own native DELETE before queueing in the
same transaction. Completion also requires the captured Registry and every
captured credential to reach their native removal result. Parent terminal state
or another request's process is insufficient. An uncertain dispatch is observed
rather than automatically replayed. Existing cancellation rules remain.

## Verification status

The original workflow source parser enumerates 517 required methods: all 506
published-390 identities and eleven new Registry methods. Each new identity is
present in its owning JUnit source. This is registration evidence only; the
original CI XML, compiled operands/C03, role/schema comparison, immutable
publication and designated native business acceptance remain pending.
