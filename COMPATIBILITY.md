# Compatibility Contract

The migration preserves established `io.cattle.*` Java packages, Maven coordinates, database schemas and migration IDs, setting keys, API resource and field names, event names, Docker labels, generated client types, and internal service identifiers. These values are persisted or consumed across repositories and are not product branding.

New operator-facing names use PastureStack and `PASTURESTACK_*`. Compatibility identifiers must be changed only with an explicit data migration, a dual-read or dual-write transition, a rollback plan, and cross-repository verification.

## Docker host policy

The unreleased candidate adds Docker Engine `29.8.0` as an exact supported
version alongside the preserved legacy ranges, `24.0.9`, and the existing
`29.4.1` through `29.7.2` interval. It does not widen the interval to admit
untested patch versions. `newest.docker.version` is `v29.8.0` so the host UI
classifies versions above it as untested rather than misreporting them as
supported.

Host Docker version classification and host firewall backend selection are
different contracts. Ubuntu version does not determine whether an operator
uses `iptables-legacy`, `iptables-nft`, or Docker's native nftables backend.
Network components must detect the host's actual active backend before
installing only their owned rules, without switching the host default or
modifying another backend. Each of those modes needs runtime acceptance on
the relevant host before this candidate is published as fully supported.

The `rancher.compose.*` setting keys and inherited executable aliases remain compatibility contracts for existing launchers. Public artifact URLs and container images are hosted under the PastureStack GitHub organization; remove an alias only after its launcher and rollback fixtures accept the replacement name.

## Host API token rollout

The Engine is the issuer for Host API access tokens. Docker socket access carries the exact `scope=dockersocket` claim. Host statistics carry a single formatted `resourceId`; container statistics carry only the Docker identifiers and formatted resource IDs that the caller is allowed to observe. Empty or broader compatibility tokens are not valid.

Deploy the issuing Engine first, allow tokens issued by the previous Engine to expire, and only then deploy a Host API version that enforces these claims. Rollback follows the reverse compatibility boundary: retain the strict issuer while rolling Host API back, and do not reintroduce empty claims.

Before release, validate clean installation, upgrade from a preserved database, rollback, API and UI behavior, subscriptions, agent events, scheduler, networking, storage, secrets, authentication, catalog, backup and restore, and both supported database engines in isolated VMs.
