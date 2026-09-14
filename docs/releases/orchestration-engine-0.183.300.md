# Orchestration Engine 0.183.300

- Make service rollback cover the same upgradeable service kinds as service
  upgrade. This includes ordinary, load-balancer, storage-driver, and
  network-driver services, while retaining the existing DNS and external
  service compatibility paths.
- Restore the previous persisted launch configuration for network-driver and
  storage-driver services during rollback. A subsequent Catalog upgrade now
  creates containers from the requested image instead of becoming a no-op
  against stale persisted state.
- Lock the service-kind contract with a focused regression test. The downstream
  Server release additionally exercises an actual Flat network driver upgrade,
  rollback, repeated upgrade, container image identity, Metadata, DNS, host
  ports, cross-host traffic, and the installed firewall backend.

This release preserves the Docker host compatibility policy and network
Metadata behavior from `0.183.299`; it does not change firewall rules or select
a host firewall backend itself.
