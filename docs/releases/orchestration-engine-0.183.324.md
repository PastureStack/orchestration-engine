# Orchestration Engine 0.183.324

This patch exposes `projectTemplate.isPublic` to non-admin API readers as a
read-only field. The admin-only create and update permission for that field is
unchanged. It also removes the misleading `remove` action link on a template
whose `accountId` does not match a non-admin caller. Private template owners
and admins retain the link; direct mutation authorization is unchanged.

The change addresses public templates, which have `accountId: null` and were
returned without `isPublic` in the v1 API. No database migration or new API
field is needed. Rolling back to `0.183.323` restores the previous response
shape and action-link behavior.

Acceptance includes the focused schema and output-filter tests, the Engine
source gate, and verification of the resulting Server assembly before rollout.
