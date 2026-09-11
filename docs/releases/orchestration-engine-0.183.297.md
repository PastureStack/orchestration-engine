# Orchestration Engine v0.183.297

- Complete the frozen `v1` hardware contract for service `launchConfig`
  payloads as well as direct containers.
- Expose `runtime`, `shmSize`, and typed `deviceRequests` with the same
  create/update permissions on both schema surfaces across all 12 applicable
  role snapshots.
- Extend the packaging regression test to reject a snapshot where either
  `container` or `launchConfig` loses these fields.
- Keep the existing `/v2-beta` contract and Docker create/upgrade conversion
  behavior unchanged.
