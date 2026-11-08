# Orchestration Engine 0.183.390

[Engine 0.183.390](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.390)
is published and requires matching Web Console **1.6.231** / Bridge **0.2.33**.
Component publication is separate from Server assembly, deployment and native
workflow acceptance.

## Reviewed Service startup arguments

The existing upgrade preview accepts the primary `command` and `entryPoint`
arrays. Omitting a field preserves it. An ordered array replaces it, `null`
clears the native setting, and `[]` remains an empty array. These distinct native
values are not normalized into one another. Inputs use the existing create
limits: 64 entries, 4,096 UTF-16 units per entry, 16,384 in total, and no NUL.
Shell strings and raw launch configurations are not accepted.

The human review captures exact before/after values. The original primary and
secondary configuration, native validation, target UUID/CAS, live role/Key
checks, one-time confirmation, native process ownership, readiness and instance
retirement checks remain authoritative. This subset edits only the primary;
secondary argument edits continue through the ordinary interface.

## Input metadata and authorization

The base `aiServiceUpgradePlan` schema adds the two nullable `array[string]`
inputs with create enabled and update disabled. Admin, project and user role
overlays each expose `command` and `entryPoint` as create/read plan inputs:
six exact role-field metadata additions. The six effective compiled fields were
verified. These metadata additions do not expand native role/Key grants;
all 1,396 operation tuples and typed enums remain unchanged.

## Component qualification

Original CI run **42505094500** passed 484 suites / 4,319 tests, with zero
failures/errors and one inherited skip. All **506** required methods passed:
502 retained identities and four new startup-argument methods. Packaged evidence
covers 91 class roots: one current and 90 byte-identical carried families.
C03 covers 503 source inputs with the same 1,396 operation tuples.

The immutable release **438432470** passed anonymous bytes/SHA256 readback for
all twelve assets and native cryptographic release verification covering
thirteen subjects. Artifact and builder Critical/High and fixable findings
remain zero under their existing qualified policies; raw lower-severity
findings and registered no-fix decisions remain separate. The workflow did not
provide a native SLSA attestation; no SLSA claim is made.

[Published metadata](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.390/engine390-release-metadata.json)
binds producer `c5899bca4c15c7c853d668d021024eeba62fc8f9`, tested tree
`cedc66cd50fe9da8aa9a7f744e946a86ca472eed` and normal merge/tag target
`d5f3c291d62f520d8b811d46f71c10c426486da6`.

At component qualification, Server **1.6.588** assembly/deployment and affected
browser/native acceptance, including the seven Stack composites, remained pending. Component qualification
does not establish deployment or complete 243-group frontend coverage.
