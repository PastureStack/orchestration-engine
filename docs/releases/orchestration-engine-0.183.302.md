# Orchestration Engine 0.183.302

- Bind tokens created by Web Console `1.6.117` and newer to the supplied
  high-entropy client session generation.
- Require a matching `X-PastureStack-Client-Session-Id` only when revoking a
  bound token. Missing, malformed, mismatched, absent, and already-revoked
  requests return idempotent `204` without deleting the token or emitting an
  expiry cookie.
- Preserve the established delete-and-expire behavior for unbound tokens made
  by legacy clients, allowing rolling upgrades without stranding old sessions.
- Add nullable `auth_token.client_session_id` through ordered Liquibase
  migration `core-126`. The historical MySQL fresh-install dump deliberately
  remains unchanged so the migration runs exactly once on clean and upgraded
  databases.
- Add DAO/model/generated-record support, focused token-resource tests, a real
  migration test, and packaged-artifact gates for the new contract.

This release does not alter OIDC, MFA, CSRF, Origin validation, cookie security,
concurrent-session policy, host compatibility, or firewall backend selection.
Use it with Web Console `1.6.117` or newer for the complete cross-tab fix.
