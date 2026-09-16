# Compatibility Contract

The migration preserves established `io.cattle.*` Java packages, Maven coordinates, database schemas and migration IDs, setting keys, API resource and field names, event names, Docker labels, generated client types, and internal service identifiers. These values are persisted or consumed across repositories and are not product branding.

New operator-facing names use PastureStack and `PASTURESTACK_*`. Compatibility identifiers must be changed only with an explicit data migration, a dual-read or dual-write transition, a rollback plan, and cross-repository verification.

## Docker host policy

Release `0.183.308` preserves the Docker host policy introduced in `0.183.299`,
which adds Docker Engine `29.8.0` as an exact supported
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
the relevant host before a Server release that consumes this Engine is
published as fully supported.

## Browser token session ownership

Web Console `1.6.117` and newer supplies a high-entropy client session
generation when creating a token and repeats it only for explicit logout. The
Engine stores that value in nullable `auth_token.client_session_id`. A bound
token cannot be revoked by a client that omits or mismatches the value; those
requests and repeated deletes return `204` without an expiry cookie. Unbound
tokens created by older consoles retain their established delete behavior so a
rolling upgrade does not strand legacy sessions.

Release `0.183.308` normalizes the authenticated current-token representation
before the ownership lookup, so both a cookie's bare key and an
`Authorization: Bearer <key>` header revoke the same matching session. It does
not accept another authorization scheme or a malformed multipart value.

The same release updates every shipped frozen v1 schema that exposes `token`
(`base`, `superadmin`, and `token`). Each snapshot retains `clientSessionId` as
a nullable password field with exact length 78, create access only, no update
access, and read-on-create-only semantics. This closes the v1-specific gap in
which authorization was correct in the dynamic schema but the frozen snapshot
silently removed the generation before token creation. Server release gates
must exercise session-bound creation and deletion through both `/v1/token` and
`/v2-beta/token`; a v2-only runtime check is not sufficient.

When `api.auth.restrict.concurrent.sessions=true`, replacement is protected by
one distributed lock per token account and authenticated account. The Engine
compares the fixed-format generations, rejects a delayed older or unbound
legacy login while a newer bound session is active, creates the replacement
before removing older tokens, and publishes the established disconnect event
only after replacement succeeds. When the setting is `false`, tokens are
created independently and the replacement path is not entered.

The generation is an ownership correlation value, not an authentication token:
it does not replace the cookie, grant access, or appear in URLs. The Web Console
must keep JWT material out of Web Storage and hold its cross-tab mutex through
the complete explicit-delete response before clearing its local state.

The token authorization overlay and every frozen token schema must retain
`clientSessionId` as a read-on-create-only input. Removing it from either API
surface turns new logins on that path into unbound legacy tokens even when the
Web Console supplies a valid generation. Separate regression tests therefore
cover both the dynamic overlay and the deserialized frozen snapshots.

## External identity types

The reviewed external identity list includes `oidc_user` and `oidc_group` in
addition to the established GitHub, Shibboleth, and LDAP types. The same
dynamic setting drives project-member schema options and Engine validation;
environment overrides may replace the list for compatible deployments, but an
active external provider does not authorize arbitrary identity type strings.
The reviewed default list must be present in a `META-INF/cattle` defaults file
that production Archaius startup actually loads; the installer-facing root
`cattle-global.properties` alone is not a Java runtime configuration source.
The core schema factory must initialize after Archaius has loaded packaged,
environment, and database settings. Schema options merge the base list with
the configured list in stable order and remove duplicates; parsing before
configuration initialization is a compatibility defect because it silently
publishes only the historical base identities.

## Bound MFA security confirmation

Authentication Service `v0.4.37` and newer can request an MFA confirmation for
purpose `oidcAccessPolicyUpdate` and a canonical lower-case SHA-256 digest of
the normalized policy request. The Engine binds both the pending challenge and
completed ticket to that purpose, digest, and authenticated account. Finishing
or consuming with a different account, purpose, or digest fails without
altering the valid owner's challenge; successful consumption is atomic and
single-use.

The new binding fields are optional so established unbound confirmation flows
remain compatible. They are correlation inputs, not bearer credentials, and do
not weaken the existing CSRF, Origin, MFA, ticket-expiry, or account ownership
checks.

The `rancher.compose.*` setting keys and inherited executable aliases remain compatibility contracts for existing launchers. Public artifact URLs and container images are hosted under the PastureStack GitHub organization; remove an alias only after its launcher and rollback fixtures accept the replacement name.

## Host API token rollout

The Engine is the issuer for Host API access tokens. Docker socket access carries the exact `scope=dockersocket` claim. Host statistics carry a single formatted `resourceId`; container statistics carry only the Docker identifiers and formatted resource IDs that the caller is allowed to observe. Empty or broader compatibility tokens are not valid.

Deploy the issuing Engine first, allow tokens issued by the previous Engine to expire, and only then deploy a Host API version that enforces these claims. Rollback follows the reverse compatibility boundary: retain the strict issuer while rolling Host API back, and do not reintroduce empty claims.

Before release, validate clean installation, upgrade from a preserved database, rollback, API and UI behavior, subscriptions, agent events, scheduler, networking, storage, secrets, authentication, catalog, backup and restore, and both supported database engines in isolated VMs.
