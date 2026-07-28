# Compatibility Contract

The migration preserves established `io.cattle.*` Java packages, Maven coordinates, database schemas and migration IDs, setting keys, API resource and field names, event names, Docker labels, generated client types, and internal service identifiers. These values are persisted or consumed across repositories and are not product branding.

New operator-facing names use PastureStack and `PASTURESTACK_*`. Compatibility identifiers must be changed only with an explicit data migration, a dual-read or dual-write transition, a rollback plan, and cross-repository verification.

The `rancher.compose.*` setting keys and inherited executable aliases remain compatibility contracts for existing launchers. Public artifact URLs and container images are hosted under the PastureStack GitHub organization; remove an alias only after its launcher and rollback fixtures accept the replacement name.

Before release, validate clean installation, upgrade from a preserved database, rollback, API and UI behavior, subscriptions, agent events, scheduler, networking, storage, secrets, authentication, catalog, backup and restore, and both supported database engines in isolated VMs.
