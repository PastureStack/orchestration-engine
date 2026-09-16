# Orchestration Engine 0.183.307

- Normalize the authenticated token transport before a session-bound logout
  checks or deletes its database record. Cookie requests provide a bare key,
  while standards-compliant API clients provide `Bearer <key>`.
- Keep the logout contract fail-closed: only a bare token key or the Bearer
  scheme is accepted, a missing or mismatched client session remains an
  idempotent no-op, and only the matching generation can revoke a bound token.
- Extend the deletion regression test to exercise the real Authorization
  header representation as well as cookie-style bare keys and reject other
  schemes or malformed multi-part values.
- Retain the production-loaded OIDC identity defaults and integrated v1/v2
  project-member schema contract fixed in `0.183.306`.

Server consumers must verify both Bearer and Cookie session-bound deletion in
the integrated candidate runtime before publishing.

Use this release with Web Console `1.6.119` and Authentication Service
`v0.4.38`.
