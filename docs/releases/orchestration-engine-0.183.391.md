# Orchestration Engine 0.183.391

Engine **0.183.391** is published for matching Web Console **1.6.232** / Bridge
**0.2.34**. Original CI, compiled qualification and immutable publication have
passed. Matching Server assembly/deployment and designated native workflow
acceptance remain separate and pending; publication does not update a running
Server or establish complete frontend coverage.

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
compiled semantics) is retained in the verified new artifact.

## Verification status

Original workflow **42520944186** passed 4,336 tests with zero failures and
errors and one inherited skip. All 523 required methods passed: 506 retained
published-390 identities, eleven new Registry methods and six new Bridge context
methods. Compiled/schema and C03 qualification passed for 11 current roots and
83 byte-identical published-390 roots, covering 506 source inputs and preserving
all 1,396 native HTTP tuples. Security and final qualification passed.

The signed producer `bcd470712a4e8d429760b02e8df337ae53d92e2b` and normal
merge/tag target `b832c463afca3cad704b6d72da5a76bf1c37d771` share tested tree
`7284073c2907b3a24ae9c24794d49b743148d332`. All twelve public assets passed
anonymous byte/digest verification; release cryptographic verification covers
thirteen subjects. See the [published metadata](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.391/engine391-release-metadata.json).

The earlier fingerprint-mismatch CI failure remains recorded and is not
qualification evidence. Designated native business acceptance remains pending;
these component results do not claim all 243 frontend groups are complete.
