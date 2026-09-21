# Orchestration Engine 0.183.310

- Treat `oidc_user` and `oidc_group` as reviewed built-in OpenID Connect
  identity types even when an older database setting overrides the packaged
  external identity list and omits them.
- Union the same built-in types into the `projectMember.externalIdType` options
  published by both API generations, preserving stable order and eliminating
  the runtime/schema mismatch.
- Retain the configured external-provider requirement and reject every unknown
  external identity type. This is an upgrade compatibility repair, not a broad
  identity-type bypass.
- Add focused tests for the stale-database override, normal OIDC identities,
  unknown identity rejection, and schema option publication.

Older database overrides that omit the built-in OIDC identity types are covered
by both the token/membership validation path and the public schema path. Server
consumers must also include Authentication Service `v0.4.42`, which reconciles
the non-secret provider contract before the final token exchange.
