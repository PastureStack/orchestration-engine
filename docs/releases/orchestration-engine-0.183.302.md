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
  migration `core-126`, together with an account-scoped lookup index. The
  historical MySQL fresh-install dump deliberately remains unchanged so the
  migration runs exactly once on clean and upgraded databases.
- Serialize restricted-session replacement under a distributed per-account
  lock. A delayed older login receives `409 ClientSessionSuperseded`; the new
  token is created before prior tokens are removed, and a failed replacement
  removes only its uncommitted token. Legacy clients cannot replace an active
  bound session. When concurrent sessions are enabled, tokens remain
  independent and bypass the replacement path.
- Add DAO/model/generated-record support, focused token-resource tests, a
  deterministic 100-run delayed-login race test, a real migration test, and
  packaged-artifact gates for the new contract.

This release does not alter OIDC, MFA, CSRF, Origin validation, cookie security,
the configured concurrent-session policy, host compatibility, or firewall
backend selection. It makes both values of the existing concurrent-session
setting race-safe. Use it with Web Console `1.6.117` or newer for the complete
cross-tab fix.
