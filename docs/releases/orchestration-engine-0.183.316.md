# Orchestration Engine 0.183.316

- Preserve local-administrator recovery while an external OpenID Connect
  provider uses `required` site access.
- Recognize only the server-encrypted local-auth payload issued by the existing
  recovery flow, whose final durable token remains bound to the active provider.
- Recheck at request time that platform security and local recovery remain
  enabled and that the stable principal is still an active administrator.
- Keep normal OpenID Connect sessions on the unchanged required allow-list;
  non-administrators, inactive accounts, malformed payloads, and disabled
  recovery continue to fail closed.

The auth reactor regression suite verifies both sides of the boundary: the
completed local password plus MFA recovery session remains usable, while
ordinary external identities still require the configured OIDC user or group.

Runtime acceptance must switch between `restricted`, required-group, and
required-user policies without locking out the local recovery administrator,
then prove each temporary account's site and environment permissions.
