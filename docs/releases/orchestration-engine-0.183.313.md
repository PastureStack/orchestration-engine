# Orchestration Engine 0.183.313

- Complete a newly provisioned external account's `account.create` lifecycle
  synchronously before the token flow enters MFA.
- Prevent a successful OpenID Connect exchange from passing a transient
  `registering` account into the MFA active-account gate.
- Keep the MFA safety boundary intact: every non-active account is still
  rejected, existing-account login is unchanged, and no provider or identity
  validation is relaxed.
- Preserve the validated `oidc_user` and `oidc_group` boundary and the
  Engine-owned stable `rancher_id` contract from 0.183.312.

The regression test proves that external account creation executes the
synchronous resource lifecycle and never uses the background scheduling path.
Runtime acceptance uses fresh Authentik users so every tested login exercises
new-account provisioning, OIDC, MFA, account activation, and session issuance
in their real order.
