# Orchestration Engine 0.183.389

This published component adds bounded Stack `finish_upgrade`, `cancel_upgrade` and
`rollback` plans through the existing preview, human confirmation and native
action flow. Native state gates, roles, live Key policy and upgrade ceilings
remain authoritative. Catalog/system ordinary Stacks are included; separate
Kubernetes/plugin owners are not routed through this typed ordinary-Stack path.

Rollback delegates to the existing Service owner. Only currently eligible
services are restored. Already finished active services and new active/inactive
services remain unchanged; added services are not deleted and removed services
are not recreated. Stack metadata is restored using the native parent reply.
A Stack with no eligible children performs only that parent metadata checkpoint.
Cancel-upgrade checkpoints the parent and does not establish that child work was
cancelled or retired. The human review reports the complete captured current
service count, not the number of child dispatches.

The Stack filter's redundant prewrite of every child's previous configuration is
removed. The registered Service rollback filter, shared by v1/v2-beta and all six
native Service kinds, remains the sole configuration restore writer before each
native Service rollback process. Original per-Service transactions, state
conflicts and error propagation remain. Stack fanout is not atomic.

Typed plans retain complete current child UUID/account/configuration CAS and
require their own durable Stack root process. Verified completion also requires
the corresponding native configuration result and the applicable child readiness
and old-instance retirement checks. Another process or parent state alone is
insufficient. Link-migration strategies remain outside this configuration-only
typed subset; their native API capability is unchanged.

Source registration preserves all 492 published required identities and adds
10 first-current required names: nine truly new tests and one existing test
whose obsolete bulk-prewrite assertion is corrected and renamed, now mandatory.
That rename does not add a JUnit test; the source method count has a net increase
of nine. Original CI verified all 502 required identities and 4,315 tests with
zero errors, zero failures and one inherited skip. Compiled-class/C03 and source
qualification passed, as did all twelve anonymous release assets and thirteen
release-attestation subjects. Matching Web Console 1.6.230 / Bridge 0.2.32 and
Compose 0.14.44 components are published and qualified. Matching Server runtime
and native business acceptance remain pending.

No dependency, role grant, serialized schema or native request header is added.
Matching Server assembly/deployment and affected native business acceptance
remain separate. Published Engine 0.183.388 evidence remains unchanged.
