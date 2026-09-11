# Orchestration Engine v0.183.296

- Expose the existing container `runtime` and typed `deviceRequests` fields to
  clients which consume the frozen `v1` API schemas.
- Preserve each role snapshot's existing create and update permissions by
  matching the established `shmSize` hardware field.
- Add a packaging regression test which loads every shipped `v1` snapshot and
  verifies the container fields and nested GPU request schema.
- Keep the existing `/v2-beta` contract and Docker create/upgrade conversion
  behavior unchanged.
