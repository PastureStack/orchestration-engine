# Orchestration Engine 0.183.304

- Preserve the Web Console's fixed-format `clientSessionId` through the shipped
  token authorization overlay as a read-on-create-only field, allowing newly
  issued browser tokens to be bound to their owning session generation.
- Retain the established idempotent logout contract: missing, stale, malformed,
  and repeated session-bound deletes cannot revoke or expire a newer token.
- Add `oidc_user` and `oidc_group` to the reviewed external identity defaults
  and to the generated `projectMember.externalIdType` options.
- Reject unreviewed external identity types even while an external provider is
  configured; the environment override remains dynamic and backward
  compatible rather than becoming an unrestricted bypass.
- Verify that the external-provider configured state follows the persisted
  provider and boolean settings after restart instead of being hard-coded.
- Add focused tests for the real token overlay, default and dynamic schema
  options, OIDC project-member conversion, unknown-type rejection, provider
  state restoration, token issuance ordering, logout ownership, and MFA login.

Use this release with Web Console `1.6.119` and Authentication Service
`v0.4.38`. It preserves the host, Docker, firewall-backend, database, and public
API compatibility policies from `0.183.303`.
