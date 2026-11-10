# Orchestration Engine 0.183.392

Machine Driver lifecycle handlers wait for external downloads or agent replies.
They now use the existing blocking process executor by default. Three waiting
driver reactivations therefore cannot occupy all three control executor workers
and delay unrelated Stack recovery after a Server restart.

The setting is `process.machinedriver.blocking=true`. Explicit deployment
overrides remain supported. Executor sizes, native process identities, resource
authorization, and Stack/Service request payloads retain their existing behavior.

Two deterministic tests exercise actual dispatch with barriers, including the
explicit override. Required CI checks and native Server recovery acceptance must
pass before this candidate is qualified for deployment.

## Build environment security

The Dapper image upgrades its existing rust-coreutils package to the official Ubuntu26.04 backport `0.10.0-1ubuntu2~26.04.1`, pinned by SHA256. Existing providers and alternatives remain selected. Sixteen findings with an available fixed version are subject to the unchanged release gate. Unfixed util-linux/expat findings and four remaining rust-coreutils findings are tracked by exact package/version/PURL, limited to Dapper and reviewed again by2026-11-17. Upstream severity differences remain disclosed; no finding is marked fixed solely by registration. The product artifact is scanned independently.
