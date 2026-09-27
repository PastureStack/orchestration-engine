# Orchestration Engine 0.183.326

The v1 user API uses a frozen serialized schema that did not contain `projectTemplate.isPublic`.
The dynamic authorization overlay in `0.183.325` exposed the public flag in
v2-beta, but could not create a field absent from that frozen v1 schema. This
release copies just that field from the current core schema into the v1 user
schema as read-only. Existing admin create and update permissions are left
unchanged. It does not widen template mutation authorization or alter any
other frozen schema.

Focused tests cover a synthetic frozen user schema, unchanged admin field
permissions, and the actual packaged v1 user schema. QA acceptance must read
all existing public templates using owner, member, restricted, read-only, and
no-access-to-matrix-project sessions through both v1 and v2-beta, then verify
write authorization using only an isolated, disposable template fixture.

No database migration is required. Rollback to `0.183.325` restores the prior
v1 omission; callers must not interpret a missing `isPublic` as `false`.
