# Engine 0.183.362

## Verified ServiceIndex cleanup ownership

Native Service scale-down and Service/Stack removal schedule `serviceindex.remove`.
Scoped API Keys previously lost the original Service owner on that child process,
so a permitted update or deletion could terminate with an authorization denial.

Only the existing native `service.activate`, `service.update` and `service.remove`
owner frames may bind this exact child cleanup. The saved worker pins Index,
Service, Stack and account identity and rechecks the original actor, current role,
Key revision, expiry and revocation. Explicit child, Service and Stack denials still
win. Generic ServiceIndex API access and unrelated network grants are unchanged.
Full, legacy and no-Key owner paths keep their existing behavior.

Eight focused regression methods reproduce the real native planner failure before
the fix and pass afterwards, including queued JSON readback, Service/Stack removal,
foreign/rebound identities and the native pool-release handler. Checkstyle reports
zero violations in the four affected Java files. The new Mockito dependency is
test-scoped only. CI/CodeQL, publication and assembled QA acceptance remain pending.

This is a prerequisite repair for existing WebMCP workflows; it does not add a new
public tool or complete the remaining frontend business groups.
