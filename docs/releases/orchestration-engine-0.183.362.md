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
test-scoped only. Required component CI/CodeQL and publication passed; assembled
Server and designated QA lifecycle acceptance remain pending.

This is a prerequisite repair for existing WebMCP workflows; it does not add a new
public tool or complete the remaining frontend business groups.

## Published original artifact

Producer `e80e9535eb9c42e33c62a9d5559466201c6659da`; merge/tag
`2159de277da7cf8acc15ff3d779b1b90883cd0b5`; tested tree
`e7929b56242f172a6b9f8bf619d84a84849b0017`. The original cattle.jar is
88,231,682 bytes, SHA256
`200471f2511e3366032bd73c4383ae91fedceee7dc62430e49dc463550ce33ee`.

Security run 41015924404, custom CodeQL 41015924387 and organization CodeQL
41015925628 passed unchanged gates. The official reports contain 413 suites and
3,851 tests, zero failures/errors and one existing skip. All eight new affected
methods passed; 431 source inputs and 1,396 HTTP operation tuples retain their
reviewed semantics. Twelve original public assets passed anonymous checksum
readback. GitHub artifact/source provenance is provided; native SLSA attestation
is not claimed. Publication does not establish assembled runtime acceptance.
