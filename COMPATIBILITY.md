# Compatibility Contract

The migration preserves established `io.cattle.*` Java packages, Maven coordinates, database schemas and migration IDs, setting keys, API resource and field names, event names, Docker labels, generated client types, and internal service identifiers. These values are persisted or consumed across repositories and are not product branding.

New operator-facing names use PastureStack and `PASTURESTACK_*`. Compatibility identifiers must be changed only with an explicit data migration, a dual-read or dual-write transition, a rollback plan, and cross-repository verification.

## Read-on-create-only responses

Candidate `0.183.333` applies the existing schema `o` flag only to real resource
creation (`POST` with no action), matching resource-manager dispatch. API Key
deactivate, activate, remove and other POST actions must not return a stored
create-only value. Existing null-redaction representation, ordinary readable
fields, create-time first-secret delivery, v1 frozen schemas and v2 overlays are
preserved. All three response wrappers share one decision. Legacy constructors
without action context keep their signatures but fail closed; callers needing a
creation response must pass the explicit create decision. No database,
authentication, authorization, proxy or stored-state migration is introduced.
Rollback restores the older POST-action exposure and is not a security fix.
Local focused verification passed 19 tests; immutable release and QA8080 native
API Key lifecycle checks are still pending.

## Volume native classification

Published `0.183.332` exposes the existing `volume.isNative` boolean as read-only
for readable Volume resources. Current role overlays retain the field; the v1
loader copies only this missing field from the current core schema into frozen
role schemas. It grants neither create nor update permission on the field and
does not widen collection/object authorization, methods, actions or other
fields. The API preserves stored true/false values and applies the established
server default only through an explicit schema contract. Client input cannot
set this server-owned classification on POST or PUT. No database migration is
required. Rolling back removes the field from affected role responses, so the
strict Web Console unallocated-volume list may again hide eligible volumes.
The signed numeric release binds built source
`7a625eee58fb2bdba83d2f008bdf7dd3c0ae4295` and WAR SHA256
`31090699e214f8e357f7fe413ce307e722b9b53177003e5de0bbbca1bc7bc3f5`.
Official CI passed 270 suites / 1,150 tests with zero failures, errors or skips;
the six release assets and three anonymous content readbacks were verified.
The exact WAR passed isolated JDK25.0.3 / H2 startup with no network, exposed
ports or platform-data mounts. Published Server `v1.6.505` consumes the exact
WAR with Web Console `1.6.168`, from source
`400f7dc8d533a5f13a555398c595b9ae42e0c454`, in immutable digest
`sha256:b3dd402cfd773b4d37ecf06f716187833e56cc6f211b7ab920dccf8dcb7366c5`.
Publisher 37072151759 and official image/component readback passed. QA8080
deployment and one restart passed HTTP 200/pong with runtime, environment and
five database counts preserved; the 504 rollback point remains. Docker health
is `null`, not `healthy`. Native Volume UI acceptance is in progress and the
full resource/role matrix remains incomplete, not an overall PASS.
An unallocated local Volume can legitimately be `inactive`; the native list
does not require `active`. Removal follows actual actions and empty bindings,
not an additional activate/deactivate cycle introduced by an acceptance tool.

## Certificate lifecycle

Engine `0.183.327` allows partial Certificate updates that omit `cert`, without
changing existing certificate or key fields. Explicit invalid `cert` values
still fail validation. The shared DELETE/remove guard also rejects v2 alternate
certificate references using the same error contract as default references.
The v1 helper path, account boundary, removed/kind query constraints, and
account teardown remain unchanged. No migration is required; rollback restores
the old partial-update failure and alternate-reference protection gap.

## Docker host policy

Engine `0.183.331` preserves imported native-container name synchronization
through exact full Docker IDs and the existing Host/Agent/account boundary.
Only running/active or stopped/inactive instance/mapping pairs are eligible;
unique nonremoved mapping, managed/service/stack exclusions and the name-only
full-row compare-and-swap remain unchanged. No schema or state-lifecycle
migration is introduced.

Published Server `v1.6.500` consumes the exact 331 WAR. Its QA8080 first
start/restart, preserved runtime/data counts and five exact Docker/model/DOM
ID-name bindings passed. This is not complete visual acceptance: Root's PNG
review remains VISUAL HOLD because four retained rollback names are clipped.
Published Server `v1.6.501`, source
`ea97b199801c277efc178430b4aa6a0d4f67d25b`, consumes that same WAR with Web165
and digest `sha256:0a671e2695eecc74d79ef666267a40e81172205f0f8b1d0b12a7dbbed446becd`.
Publisher 36980705366 and immutable readback passed. Its QA8080 first
start/restart passed with zero runtime/data-count differences and Docker health
`null`; a separate COUNT-derived proof binds six exact full Docker IDs/names.
Separate fresh native initial/reload checks and Root's actual PNG review passed
for the six complete Host1 names and five rollback suffixes at zh-tw desktop
width 1440, without detail/action overlap. One menu open/close cycle and one
actual server WebSocket message on reload were observed with zero resource
writes. This does not cover mobile, all locales or long-term WebSocket behavior.
Server500's visual HOLD is historical and unchanged; the full resource/role
matrix is INCOMPLETE. See the
[331 release note](docs/releases/orchestration-engine-0.183.331.md) for the
immutable Server source/digest and evidence boundaries.

Release `0.183.319` preserves the Docker host policy introduced in `0.183.299`,
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

## Shared Default environment

Release `0.183.317` changes only the default-environment provisioning policy.
When `project.create.default=true` and `project.default.provisioning=shared`, a
successful external login reconciles the account into the project whose stable
UUID is `adminProject`. The membership identity is the internal `rancher_id`
for that exact account and the baseline role is `member`.

Reconciliation is idempotent and serialized by the existing project lock. If
any identity in the authenticated set already has an active direct or group
membership, that role is authoritative and no baseline membership is added.
This preserves explicit owner, restricted, read-only, or no-access decisions.
The migration does not delete the personal environments made by older releases
or move their stacks; returning accounts gain the shared Default on the next
successful login. Operators can retain the former first-login behavior with
`project.default.provisioning=personal`, or disable default provisioning with
`none`.

Release `0.183.318` keeps the site access modes distinct after shared Default
membership is established. For `restricted`, an already resolved active
account contributes its stable `rancher_id` before the project-membership
check, so a returning member can authenticate even when the current external
claim is not on the site allow-list. This enrichment occurs only after exact
identity-to-account resolution. `required` remains allow-list only and never
receives the stable identity before authorization; inactive or unknown
accounts therefore gain no new path into the platform.

Release `0.183.319` closes the concurrent reconciliation gap. The authenticated
identity set is rechecked and any baseline stable-account membership is created
under the same project lock used by membership replacement. A simultaneous
direct or group role assignment therefore remains authoritative instead of
being weakened by a later `member` grant. The release gate exercises this
ordering with a deterministic barrier for 100 iterations and separately covers
`shared`, `personal`, `none`, invalid-mode, and legacy-disabled behavior.

Release `0.183.320` preserves these roles and membership-write semantics. It
only requires the token policy to authorize the *queried* project before a
`projectMembers?projectId=` collection is loaded. A selected-project request
header cannot authorize reading another project's member list; both v1 and
v2-beta use the same check.

Release `0.183.321` keeps that project boundary and aligns direct member-ID
reads with collection visibility: only active, non-removed membership rows are
returned. Historical rows remain in the database; neither membership writes
nor role semantics change. v1 and v2-beta share this resource manager.

Release `0.183.322` adds an account-purge retry for networks whose removal
timestamp was recorded while the network is still `removing`. It does not
change network creation, ordinary removal, API schema, project roles, or
membership behavior. Release archives now exclude the development-only
`cattle-dev` module and its defaults; CI builds the release profile and
checks every bundled JAR before a package can pass its artifact gate.

Engine `0.183.323` updates FreeMarker to `2.3.35` for CVE-2026-84939. The
template configuration retains its explicit `VERSION_2_3_0` compatibility
setting. This dependency update does not change API, database schema, or
template configuration.

Engine `0.183.326` restores `projectTemplate.isPublic` in non-admin v1 API
responses as a read-only field. In `0.183.325`, v2-beta exposed the field but
the frozen v1 user schema omitted it. The admin create and update permission
remains unchanged. Public templates still have no owning `accountId`; their
`remove` action link is omitted for non-admin callers, while private template
owners and admins retain it. Direct update and delete authorization remains
enforced by the existing policy. There is no database migration.
The `v0.183.324` tag is source-only and has no published release artifact.

## External identity type upgrades

`oidc_user` and `oidc_group` are built-in OpenID Connect identity types. An
older database setting may replace the packaged external-type list and omit
them, so release `0.183.310` always unions these two reviewed types into API
schema options and Engine validation. Other configured provider types remain
dynamic, unknown identity types remain rejected, and the external provider
must still be configured before any external identity can be transformed.

Release `0.183.311` treats a successful external Authentication Service token
exchange as the provider boundary for the identities returned by that same
exchange. This avoids rejecting a valid login because a separately propagated
provider-state flag is stale. It does not broaden the identity contract:
`oidc_user` and `oidc_group` remain explicitly reviewed, unknown types still
fail closed, and project-member transformations still require the active
external provider.

Release `0.183.312` validates those external identities before access-policy
evaluation or account mutation, then handles the Engine's own stable account
identity as a separate internal boundary. Only a `rancher_id` that resolves to
the account authenticated by that token is accepted; a mismatched or
provider-supplied platform identity is rejected. This preserves upgraded OIDC
login without allowing an external identity to claim another platform account.

Release `0.183.313` preserves that identity boundary and makes the external
account lifecycle ordering explicit. A newly created account completes its
synchronous `account.create` process and reaches `active` before MFA begins.
The MFA service continues to reject every non-active account; this release
does not bypass that safety check or change the existing-account login path.

Release `0.183.314` closes the remaining v1 compatibility gap. The serialized
v1 `projectMember` schema predates OpenID Connect and can otherwise reject
`oidc_user` and `oidc_group` even though the current core schema and login path
accept them. When loading that frozen schema, the Engine merges the current
core options into `projectMember.externalIdType`, de-duplicates them, and
retains the historical provider options. The merge is deliberately limited to
that field: unrelated frozen enums are not widened, and runtime identity
validation continues to reject unknown types.

Release `0.183.315` corrects an upgraded-installation identity ownership bug.
An `authIdentity` credential created during unauthenticated OIDC login could be
owned by the built-in `token` account even though the verified user account was
supplied explicitly. Subsequent logins could therefore authenticate as that
internal account and lose normal API visibility. New credentials now verify
their persisted owner. Login lookup accepts only user and administrator owners;
a legacy link is moved only when its existing owner is the built-in token
account and its provider, type, external ID, digest, and target account all
match exactly. Valid links are never reassigned automatically, so collision and
account-takeover protection remains fail-closed.

Release `0.183.316` preserves the emergency local-administrator recovery path
under external `required` site access. A recovery session bypasses the external
allow-list only when the server-encrypted payload is a local-auth token, local
recovery and platform security remain enabled, and the stable principal still
resolves to an active administrator. Durable sessions remain bound to the
active external provider, so provider switches continue to invalidate them.
External OIDC sessions, inactive accounts, non-administrators, malformed
payloads, and disabled recovery remain subject to the normal access policy.

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
environment overrides may extend or narrow provider-specific compatibility
types, but cannot remove the two built-in OIDC types. An active external
provider still does not authorize arbitrary identity type strings.
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

Authentication configuration updates must preserve the caller's platform
authorization credential through the Engine-to-Authentication-Service proxy.
The provider access token remains appropriate for read-only identity and
configuration enrichment, but cannot substitute for the operator session when
the Authentication Service consumes a bound MFA confirmation. Server release
gates must exercise the public `/v1-auth/config` path rather than validating
only the Authentication Service's loopback endpoint.

The `rancher.compose.*` setting keys and inherited executable aliases remain compatibility contracts for existing launchers. Public artifact URLs and container images are hosted under the PastureStack GitHub organization; remove an alias only after its launcher and rollback fixtures accept the replacement name.

## Host API token rollout

The Engine is the issuer for Host API access tokens. Docker socket access carries the exact `scope=dockersocket` claim. Host statistics carry a single formatted `resourceId`; container statistics carry only the Docker identifiers and formatted resource IDs that the caller is allowed to observe. Empty or broader compatibility tokens are not valid.

Deploy the issuing Engine first, allow tokens issued by the previous Engine to expire, and only then deploy a Host API version that enforces these claims. Rollback follows the reverse compatibility boundary: retain the strict issuer while rolling Host API back, and do not reintroduce empty claims.

Before release, validate clean installation, upgrade from a preserved database, rollback, API and UI behavior, subscriptions, agent events, scheduler, networking, storage, secrets, authentication, catalog, backup and restore, and both supported database engines in isolated VMs.
