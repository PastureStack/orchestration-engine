# Orchestration Engine 0.183.312

- Validate every identity returned by the external Authentication Service
  before access-policy evaluation, account lookup, or persistent mutation.
- Preserve the built-in `oidc_user` and `oidc_group` contract even when an
  upgraded database still contains an older external-type list.
- Separate external identities from the `rancher_id` that the Engine adds
  after resolving or creating the authenticated platform account.
- Accept that stable platform identity only when it resolves to the same
  authenticated account. Mismatched, missing, and unknown types fail closed.
- Keep project-member input and generic identity lookup bound to the configured
  provider; this change is limited to the successful external login response.

Only the Engine-generated stable identity for the authenticated account is accepted.
The Authentication Service cannot use this path to claim a different local
account. Regression coverage includes the sanitized Authentik response shape,
older database overrides, missing and unknown external types, forged
`rancher_id`, and the matching stable-account identity added during token
creation.
