# Orchestration Engine 0.183.305

- Make the core schema factory explicitly depend on completed Archaius startup,
  preventing public schemas from being frozen before packaged, environment,
  and database configuration sources are available.
- Publish `oidc_user` and `oidc_group` in the integrated
  `projectMember.externalIdType` options for both API generations.
- Merge base and configured identity options in stable order and remove
  duplicates while preserving dynamic deployment overrides.
- Add a Spring-backed regression test that uses the real base
  `projectMember.json`, a lazy configuration initializer, the actual schema
  factory, and the production post-processor list. The test fails if schema
  parsing can run before configuration initialization.
- Retain all token session binding, idempotent owned logout, reviewed identity
  validation, provider restoration, MFA policy confirmation, Docker host, and
  firewall-backend contracts from `0.183.304`.

Use this release with Web Console `1.6.119` and Authentication Service
`v0.4.38`. Server consumers must verify both `/v1/schemas/projectMember` and
`/v2-beta/schemas/projectMember` from the integrated runtime before release.
