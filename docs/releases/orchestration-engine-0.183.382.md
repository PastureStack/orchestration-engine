# Orchestration Engine 0.183.382

[Engine 0.183.382 is published](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.382). It repairs false API Key authorization denials in the native load-balancer Agent lifecycle. Agent setup runs before `Instance.agentId` is written, and its private Agent account and `agentApiKey` do not belong to the caller's Project. Authorization now proves the exact native Factory relationship for those dependencies.

## Changes and compatibility

The supported callers retain their original operation: direct load-balancer create, update/activation, upgrade/rollback and retirement cleanup, plus Stack `activateservices` for an inactive load balancer. Stack callers must resolve through the verified Instance/Agent and a unique persisted expose map to a load balancer in that exact authorized Stack and Project. Removal covers only the exact Agent, its primary or authorized secondary accounts, and their Agent credentials. Captured cleanup can finish after its exact parents are removed; a revived Instance cannot reuse that proof.

The framework records native ancestry; the resolver retains the load-balancer, Factory URI, role, ownership and root restrictions. Queued work reloads the persisted identities and rechecks current owner/schema authority, Key revision, expiry and revocation. Custom grants require the original root operation, and explicit child denials take precedence. This adds no general Agent access, system/Full bypass, Catalog/Host delegation, native route, role-field grant, public tool or human-approval bypass. The default `event:///instanceId=<id>` Factory relationship remains required.

Existing verified Instance/Volume frames may omit the native process name. The framework treats that absence as a non-match rather than passing null into an immutable Set. This preserves the older valid frames and their normal mount/cleanup paths. The new Agent dependency grant separately requires a nonempty captured parent process; an empty parent cannot borrow it.

## Verification and publication

Original CI [42327309344](https://github.com/PastureStack/orchestration-engine/actions/runs/42327309344) passed 451 suites and 4,101 tests with zero failures/errors and one inherited skip. All 286 required methods passed. Custom CodeQL [42327309265](https://github.com/PastureStack/orchestration-engine/actions/runs/42327309265) and organization CodeQL [42327311381](https://github.com/PastureStack/orchestration-engine/actions/runs/42327311381) also passed. The tested producer `6fc12797` was normally squash-merged as `23acbf96` with the same tested tree.

The null-frame regression was reproduced by four controls. The 54 originally affected CI methods and four guards then passed together in one JVM/main thread without resetting EngineContext; those original test sources were unchanged. Earlier failed CI and local regression evidence are retained. C03 retains 1,396 operation tuples across 472 source inputs; this documentation update changes no program or inventory input.

The original packaged JAR and compiled role schemas were verified. All twelve public assets passed anonymous size/SHA-256 readback, and GitHub's immutable-release attestation verified thirteen subjects. The [published metadata](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.382/engine382-release-metadata.json) records the exact producer, artifact and verification evidence. Its preparation-time status is superseded by the published immutable release. This component workflow did not provide SLSA attestations.

## Assembly and native acceptance boundary

Component CI and publication are complete. Matching Server580 assembly, deployment and native TCP acceptance remain pending. These component results do not establish the fourteen native TCP criteria, eight native Stack graph criteria or complete frontend/WebMCP coverage. The separate ordinary-Service dependency-link replacement work is not included.

The existing exact-root completion observer remains unchanged: arbitrary descendant metadata cannot project an intentional nested authorization denial as the root outcome, and operation reads remain subject to current authorization.

For rollback, restore the previous qualified Server assembly and matching components through the [Server installation guide](https://github.com/PastureStack/server#quick-start). Do not replace a running Server with a standalone Engine JAR. This repair adds no data migration.
