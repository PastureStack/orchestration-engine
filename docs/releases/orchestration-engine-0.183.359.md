# Orchestration Engine 0.183.359

[Published release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.359)
contains the original qualified CI bytes, without a publication rebuild.

## Behavior

Named Catalog discovery and supported typed Compose Stack launch retain exact
source revision, target and human input bindings. The Engine checks current
destination read/create/update operations and uses the ordinary Catalog/Compose
owners. No arbitrary YAML/URL, Project PUT grant or model approval is introduced.
Public Engine proxies reject the private Catalog AI namespace, including
redirect targets. The typed gateway checks the caller before using its fixed
loopback transport; loopback alone is not external caller authority.
Launch remains opt-in with `api.ai.catalog.launch.enabled=false`.
Unsupported graph types and external existing volumes remain pending.

Authorized public reference IDs use a typed envelope separate from the native
mutation graph. Private plan and delegation digests bind both parts; the existing
Compose wire and renderer stay unchanged. The transform handler always invokes
its policy SPI, whose owner distinguishes ordinary caller-data conversion from
proof-bearing delegation. Legacy conversion, full proof verification and missing
provider rejection retain their existing semantics. No CodeQL rule, threat model,
severity gate or suppression is changed.

The native launch materializer is registered with Spring. A dependency-closure
test covers all configured Catalog launch collaborators, including this bean,
and the operation source inventory also binds the actual API bean configuration.

Stack/Service Archive requires completely stopped eligible workloads and the
ordinary owner lock/fresh eligibility read; system/infra resources remain
ineligible. Restore clears only that target metadata, never starts it, and leaves
parent/child flags and mounted data intact.

The scoped managed cleanup resolver accepts only verified native forward
Service/SEM transitions with stable UUID/owner ancestry. It still checks live
Key expiry/revision, roles and explicit child DENY. A controlled native barrier
reproduced the transition race; this does not prove one unrecorded historical
interleaving. Ordinary Full/legacy paths and independent volume authority stay.

A durable resource operation records terminal background authorization denial
through an exact server-private root/child binding. A monotonic scalar marker
survives early failures and stale whole-document writes; a late success cannot
overwrite it. Current own-plan read authorization still applies. The safe result
is failed, verifiedResult=false, ResourceChangeAuthorizationDenied with its
original operation ID; partial effects require inspection, never blind retry.
Transient, delayed, cancelled or unacknowledged work is not invented as success.
The existing terminal removed-resource receipt retains its separate bound-safe
contract: current actor/project/role/object policy and original live Key/revision,
without returning the removed domain object or claiming a fresh target-selector
evaluation. Create preview is not described as already having read a future target.

## Validation and rollout boundary

The required [component gate](https://github.com/PastureStack/orchestration-engine/actions/runs/40966528986)
and [CodeQL verification](https://github.com/PastureStack/orchestration-engine/actions/runs/40966528987)
passed. CI executed 3,748 tests in 396 suites with zero failures/errors and one
existing skip. Original artifact 12939175361 and all twelve published assets
were independently verified by anonymous readback.

| Immutable coordinate | Value |
|---|---|
| Producer | `eac3fde5fc7779b6107e2702785120a9c591927b` |
| Merged/tag source | `c11979a7426d942df3035583e4d0dea8f1a2553f` |
| Shared tested tree | `76540a38dce4a5ff7d90a1f1881bc1e07f1bb92d` |
| Signed tag | `v0.183.359` |
| Original cattle.jar SHA-256 | `07e7de2548bd98d5209bf3594d69fe5b9a5d7ddba36eed4b6b6e4406022c3f90` |

The release retains its raw/effective scan and exact vendor-proof assets;
passing the applicable severity gate is not a claim of zero reported CVEs.
Server559 assembly and designated real browser/role/cross-ID workflows and
new weak-model acceptance remain pending. Previous unaffected evidence and
original failures are retained; the release does not complete all 243 frontend
business groups. Documentation commits do not replace the artifact's producer
or move the published tag. No runtime dependency is changed.

Use the matching Web Console 1.6.200/bridge 0.2.2, Catalog 0.20.17 and Compose
0.14.40 contracts. Roll back through the assembled Server procedure while
preserving data, receipt rows and existing configuration. Do not replay a revoked,
cancelled, denied or uncertain operation. Existing release tags remain immutable.
