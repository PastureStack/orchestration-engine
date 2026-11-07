# Orchestration Engine 0.183.384

## Changes and boundaries

This release adds three bounded typed subsets:

- Ordinary-Service configuration upgrades accept native port strings and a
  closed health-check object, including a same-image upgrade. Omitted settings
  remain unchanged; `ports: []` and `healthCheck: null` explicitly clear them.
  Complete primary/secondary, environment, volume, security, link and hardware
  configuration remains subject to native snapshot CAS and verified readback.
  Advanced/quorum health checks and unsupported strategy/plugin settings remain
  outside the typed subset.
- Builtin-local logical Volume creation returns a new, precisely identified
  detached record. It does not allocate storage on a Host, mount it, or enable
  other storage drivers or private plugin options.
- `container_logs` returns actual untrusted application text under the native
  Container LOGS action and current role, Key and Cookie-session authority.
  Read metadata alone grants no logs access. A request-bound server-only ticket
  is single-use; broker output is bounded to 200 lines and 64 KiB, with a
  five-second dial/read deadline and cancellation. This is not a five-second
  end-to-end DAO/audit guarantee or an arbitrary application-secret redactor.
  Audit records fixed metadata, never log text, tokens or tickets. Existing GUI
  Logs, Terminal and ordinary native APIs keep their existing behavior.

The native image/cache ensure and Full-owned cleanup now recognize the exact
`loadBalancerService` subtype of an authorized Service root. Verified parent
frames, persisted ownership/relations and live Key revision, expiry, role and
schema ceilings remain; five regressions cover this admission correction.

An ordinary-Service `update_links` plan with an exact CAS-stale dependency set
remains visible for human cancellation after fresh actor, role, bound Key and
reviewed target identities pass. Other authorization failures/conflicts remain
excluded. Confirm/apply retain their full CAS rejection. Two regressions cover
cancellation and the denial boundaries; the matching Console disables Review
and displays the existing localized stale-plan warning.

## Component verification

The immutable [`v0.183.384` release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.384)
uses signed producer `6ead99132f575b05c564f94882f59088f19e7511`, tree
`866f6f3d132f620bc0c220fbfcab2c059f6c7225`, and same-tree merge
`3b96f1da2b60ffdb85856963b64ce747f436d2da`.

[Security/package CI](https://github.com/PastureStack/orchestration-engine/actions/runs/42358110324),
[custom CodeQL](https://github.com/PastureStack/orchestration-engine/actions/runs/42358110220)
and [organization CodeQL](https://github.com/PastureStack/orchestration-engine/actions/runs/42358110997)
passed for that producer. The original CI artifact reports **4,216 tests in
471 suites, zero failures/errors and one inherited skip**. All **402 required
methods** are observed: the prior 330, 65 typed-feature cases, five image-admission
cases and two stale-inbox cases. The original packaged artifact closes **71
class roots** (21 affected production roots plus 50 same-source carried roots).

C03 retains **487 source inputs and all 1,396 HTTP tuples**, with 1,397 executed
projection rows. The three existing role overlays add only the five reviewed
typed DTO fields per role; native HTTP admission and role grants do not expand.
Frozen v1 source SER compatibility, packaged schemas and fresh effective typed
schema projections are qualified separately, without borrowing historical
compiled classes. Original artifact/SBOM provenance is retained; all 12 public
assets passed anonymous byte verification and the native GitHub release
attestation passed cryptographic verification. This workflow provides no native
SLSA attestation, and no SLSA claim is made.

## Native acceptance and installation

Component qualification is not native acceptance. The affected Service upgrade,
logical Volume and bounded logs workflows, their role/Key/revocation cases,
52-profile review views and the Go broker/runtime framing still require the
matching qualified Server and affected native acceptance. Historical failures
and scoped results remain separate; neither this release nor source mapping
establishes all 243 frontend workflow groups.

Use matching Web Console 1.6.224 / Bridge 0.2.26 through the
[current qualified Server installation](https://github.com/PastureStack/server#quick-start).
Do not replace a running Server with the Engine JAR alone or infer a Server
digest/deployment result from component publication.
