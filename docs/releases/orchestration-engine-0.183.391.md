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

## Scoped Bridge environment identity

The existing private `aiResourceViews` endpoint admits `bridge_context` only
for an AI-purpose Key at its expected live revision. Ordinary readable Project
flows keep their existing behavior. For a scoped navigation fallback, an
ordinary authorized child read, exact current UUID and parent ancestry, current
role and Key policy, and a final identity recheck are required. An explicit
parent read denial wins. The result contains only id, UUID, name and state and
cannot authorize a later business request. General-purpose Keys and Cookie
requests do not acquire this private view.

The private schema view enum is exactly `stack_graph`, `container_logs`,
`bridge_context`; the public MCP view enum remains the original two entries.
There is no new role field, native route, native action or native grant.
Published-390 startup metadata (six role inputs, two base fields and six
compiled semantics) must remain in the new compiled artifact.

## Verification status

The original workflow source parser enumerates 523 required methods: all 506
published-390 identities, eleven new Registry methods and six new Bridge context
methods. Each new identity is present in its owning JUnit source. The unchanged
normal C03 extractor covers 506 inputs and preserves all 1,396 native HTTP
tuples. Source roots are 11 current and 83 published-390 carry candidates; the
carry classes still require exact comparison with the new original CI artifact.
This is source registration only; original CI XML, compiled operands/C03,
role/schema comparison, immutable publication and designated native business
acceptance remain pending. The earlier fingerprint-mismatch CI failure is
retained and is not qualification evidence.
