# Orchestration Engine 0.183.309

- Preserve the authenticated PastureStack caller credential for administrative
  `POST /v1-auth/config` requests. Authentication Service can therefore consume
  the one-time, actor-, purpose-, and digest-bound MFA confirmation as the same
  operator that requested the access-policy change.
- Keep the external identity provider access token for read-only auth config and
  identity enrichment. The change is intentionally scoped to the mutating
  configuration route and does not weaken provider lookup or Engine role checks.
- Normalize the generated provider bearer header without leading whitespace.
- Add regression coverage for the mutating config boundary, read-only provider
  paths, unrelated endpoints, method case normalization, and null paths.

Server consumers must verify a bound OIDC policy confirmation through the public
`/v1-auth/config` route. A direct request to Authentication Service port 8090 is
not sufficient because it bypasses the credential-selection boundary fixed by
this release.

Use this release with Web Console `1.6.119` and Authentication Service
`v0.4.38`.
