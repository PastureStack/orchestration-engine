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
