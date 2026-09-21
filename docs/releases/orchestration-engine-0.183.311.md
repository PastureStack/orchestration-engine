# Orchestration Engine 0.183.311

- Treat the successful external Authentication Service token exchange as the
  provider boundary for identities returned by that same exchange.
- Keep the reviewed external identity allowlist mandatory. `oidc_user` and
  `oidc_group` are accepted, while unknown or missing types remain rejected.
- Keep the generic identity and project-member paths bound to the currently
  configured provider; this is not a broad provider-state bypass.
- Add focused regression coverage for a valid OIDC token returned while the
  separately propagated provider flag is stale, and for fail-closed unknown
  identity types at the external token boundary.

The external token exchange is the provider boundary only for identities returned
by that successful request. This removes the duplicate, timing-sensitive
provider-state check that caused incognito OIDC login to end with
`Identity externalIdType is invalid` after Authentication Service had already
validated the issuer and subject. Server consumers must retain Authentication
Service `v0.4.42` and the reviewed OIDC identity-type contract.
