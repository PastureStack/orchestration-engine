# Orchestration Engine v0.183.378 candidate

This candidate adds a configured API Key policy view and the ordered TCP load-balancer creation subset to the existing typed resource tools. The policy view preserves all native rules and deny semantics, including the legacy virtual Full projection. It reports incomplete owner-capability previews explicitly; it does not claim effective permission or current credential usability, expose credential data, or add a grant.

TCP creation resolves fresh, authorized Stack and backend Service references, requires an explicit startOnCreate choice and exact human approval, and dispatches one normal native create. Listener access, source/target ports and backend identity are preserved. The subset uses the server-managed load-balancer image and scale 1; HTTPS/TLS/HTTP, certificates, selectors, custom configuration and scheduling remain unsupported rather than being downgraded.

An inactive load balancer can use the shared Service start/stop workflow with empty parameters and a separate human-approved plan. Native schema, role, Key, parent Stack, revision and durable claim checks remain authoritative. Receipts require deployment readiness or stopped-instance convergence while preserving the approved identity and configuration. Remove, scale and upgrade are not added for this subtype.

The normal CI required-case list contains 256 exact methods: the original 238 and 18 added cases. Full module CI, original artifact qualification, matching Web/Bridge/Server assembly and native business/weak-model acceptance are pending. No local focused result is a native acceptance result, and no complete frontend group or new inventory denominator is claimed. Dependency, security, runtime and release gates remain unchanged.
