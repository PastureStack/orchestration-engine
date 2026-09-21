# Orchestration Engine 0.183.315

- Persist internal authentication credentials for the explicitly selected
  account and verify ownership after creation, instead of trusting request
  context defaults during unauthenticated OIDC login.
- Exclude built-in and service accounts from login-identity resolution; only
  active user and administrator accounts may own a login identity.
- Repair an existing `authIdentity` link owned by the built-in `token` account
  only when the provider, external identity type, external ID, link digest, and
  target account's external identity all match.
- Keep valid cross-account links fail-closed with `IdentityAlreadyLinked` and
  retain the explicit reassignment workflow for every intentional move.
- Apply the same explicit ownership check to identity-proof use records and
  provider-switch tickets, the two adjacent internal credential creation paths.

The focused regression suite reproduces the transient token-account overwrite,
proves exact legacy repair, and rejects both a mismatched target identity and a
link already owned by another login account.

Repeated-login acceptance must prove that every session resolves to the
same user account and retains its site and environment permissions.
