# Orchestration Engine 0.183.343 — Method/selector binding

## Actual dispatch and operation authorization

The prior classifier could let an action or link selector mask the real HTTP
operation. A PUT or DELETE decorated as a read/export/log link could therefore
be evaluated under a different grant from the mutation actually dispatched.
The classifier now uses the actual dispatch method and only the selector that
the resource handler executes. PUT/DELETE retain update/delete. GET/HEAD retain
ordinary read or the invoked sensitive export/link operation; an ignored
action query cannot replace that link. Only POST invokes registered actions,
including upgrade; unknown actions do not become invented grants.
This aligns permission evaluation with the actual sink, not a new API route.

## Queued custom-Key boundary

Existing queued metadata can carry the same old operation-classification
mismatch. Before execution, custom-Key work recomputes the captured method/name
operation and rejects a contradictory stored operation before process work.
It does not restore a revoked or expired Key or remove live owner checks.
Full and untouched legacy Keys retain the original owner/lifecycle path; they
are not reclassified into a custom scope. No queued-job replay feature,
database migration, role grant or broader mutation allowance is introduced.

## Focused source evidence and qualification boundary

21 focused source cases passed in two separately executed scopes: 12 ingress
method/selector cases and 9 queued regressions/related controls. These are source
tests, not native HTTP/database or all-role evidence; the ingress scope was not
replayed to produce the queued result.
Standard read/export and registered POST-upgrade behavior, deny precedence,
expiry, audit and owner/full/legacy contracts are preserved by the scoped change.
The earlier 342 note and its native write-only evidence remain historical proof
for their own source, not proof of the decorated-method fix.
Typed all-actions C-03 and complete role/resource/native acceptance need their
own evidence and are not closed by these focused scopes.

Component source validation, original packaging, immutable publication/readback
and assembled-Server native qualification are independent gates. Read the
corresponding Server release record for the actual image identity and native
qualification; this component note does not assert a deployed image or complete
API Key matrix PASS. Published source/archive identities come from the actual
release assets and their receipt, not invented source or WAR checksum values.
Use the complete compatible Server image rather than replacing a runtime WAR.
Preserve configuration and volumes; do not weaken Key policy as a workaround.
