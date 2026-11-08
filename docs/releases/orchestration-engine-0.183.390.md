# Orchestration Engine 0.183.390 candidate

This source candidate is not published or deployed. It requires matching Web
Console 1.6.231 / Bridge 0.2.33 and a separately qualified Server assembly.
Existing published component and runtime evidence remains unchanged.

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

## Validation status

The startup-argument source candidate passed four new and six directly adjacent
JUnit methods locally. Integrated release qualification is still
pending. Every new required method must be observed in the original CI report;
source registration or a green job alone is insufficient.

Matching immutable publication, QA deployment and affected browser/native
acceptance remain separate. There is no production deployment in this work.
