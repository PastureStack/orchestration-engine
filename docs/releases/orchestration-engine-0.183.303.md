# Orchestration Engine 0.183.303

- Bind OIDC site-access expansion challenges to the authenticated account,
  fixed purpose `oidcAccessPolicyUpdate`, and a canonical lower-case SHA-256
  request digest.
- Carry the same binding into the completed confirmation ticket and require an
  exact match when Authentication Service consumes it.
- Consume matching tickets atomically and exactly once. Expired, replayed,
  wrong-account, wrong-purpose, and wrong-digest attempts fail closed.
- Preserve a valid owner's challenge when another account attempts to finish
  it; unauthorized callers cannot invalidate the challenge as a side effect.
- Keep purpose and digest optional for existing unbound MFA confirmation flows,
  preserving the established API contract while allowing new clients to opt in
  to the stronger operation binding.
- Update live and frozen administrator and user schemas, then verify their
  create/read-only permissions and serialization artifacts.
- Add deterministic lifecycle tests for bound begin, finish, consume, expiry,
  replay, account ownership, purpose, digest, and legacy compatibility.

Use this release with Web Console `1.6.118` and Authentication Service
`v0.4.37` for the complete OIDC site-access policy update flow. It retains the
`0.183.302` session-bound logout protection and does not alter host, Docker, or
firewall backend policy.
