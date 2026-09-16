# Orchestration Engine 0.183.308

- Publish the session-bound `clientSessionId` input in every frozen v1 token
  schema (`base`, `superadmin`, and `token`) as a nullable, sensitive,
  fixed-length, create-only field. This prevents `/v1/token` from silently
  discarding the browser generation and creating an unbound legacy token.
- Add a deserialization regression test that discovers every frozen schema
  exposing `token` and verifies create/update/read-on-create/nullability/type
  and exact-length semantics rather than relying on a string-only assertion.
- Add source and packaged-artifact gates that prevent a future release from
  omitting the field. Snapshot semantics are validated in the test suite rather
  than by a separately shipped deserialization utility.
- Retain the `0.183.307` transport normalization: cookie keys and standard
  Bearer values resolve to the same token key before ownership checks, while
  malformed or unsupported authorization schemes remain fail-closed.
- Retain the production-loaded OIDC identity defaults and integrated v1/v2
  project-member schema contract from `0.183.306`.

Server consumers must verify creation, mismatched deletion, matching deletion,
and repeated idempotent deletion through both `/v1/token` and
`/v2-beta/token` in the integrated candidate runtime before publishing.

Use this release with Web Console `1.6.119` and Authentication Service
`v0.4.38`.
