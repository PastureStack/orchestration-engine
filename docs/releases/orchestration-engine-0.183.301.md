# Orchestration Engine 0.183.301

- Restore every upgraded child service's previous primary and sidekick launch
  configuration before a Stack rollback process is scheduled. This covers the
  Stack action used by Catalog-managed network and storage drivers rather than
  only the direct service action.
- Reuse the direct service rollback helper so Stack and service rollback retain
  one launch-configuration contract. Services without an applicable previous
  configuration are not rewritten.
- Lock the real Stack action path with a focused regression test. The downstream
  Server release additionally exercises an actual Flat network stack upgrade,
  rollback, repeated upgrade, container image identity, Metadata, DNS, host
  ports, cross-host traffic, and all supported installed firewall backends.

This release preserves the Docker host compatibility policy, network Metadata
behavior, and direct service rollback support from `0.183.300`. It does not
change firewall rules or select a host firewall backend itself.
