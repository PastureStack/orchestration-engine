# Engine 0.183.338

Engine 0.183.338 is published as an independent component, not a new Server image.
Server v1.6.521 still packages Engine 0.183.337 and Web Console 1.6.181.
The immutable `v0.183.338` tag binds tested producer
`b41160c36039f3f03df5bff4de21cda70e7aaf59`; documentation-only commits do not
change the artifact source. The `cattle.jar` SHA-256 is
`f4d4fe23f88d6120b17536e877b8ec0cbd74ef2b05c42239ff4c998467443667`.

## Scoped Key authority boundary

A custom resource grant must not create credentials, change account roles or
identities, enroll authentication factors, or change authentication/security
settings to obtain authority outside that Key's policy. Custom Keys reject these
security-management writes with `ApiKeyRestrictedDelegationUnsupported` and the
existing durable denial audit. Full and untouched legacy Keys retain the normal
owner authorization pipeline; no existing owner permission is added.

Ordinary account metadata remains editable under the normal Key rules, including
full account representations containing unchanged role and identity fields. The
unchanged security fields are removed before persistence so a concurrent legitimate
role reduction or identity change cannot be overwritten by that old representation.
Both typed and numeric account IDs use the same authority check.

Setting targets are resolved to their stored names before classification. Numeric
and typed aliases cannot disguise an authentication setting as an ordinary setting;
a body name cannot override the URL target. Ordinary settings, resource writes and
authorized reads retain their normal policy checks.

## Executable WAR and logging

The standalone gate validates the actual executable WAR with isolated H2,
temporary directories and loopback ports. Logback output conditions now use
PropertyEqualityCondition. The optional Logstash syslog output has its Archaius
switch and required layout; existing defaults are retained.

## Verification status

Focused source verification covers 24 governance cases, 19 authorization cases,
11 policy-management cases and two v1 read-contract cases. The 69 target-resolver
cases passed before the later account-only review fixes and remain applicable.
The independent review's numeric-ID regression and stale-full-DTO authority replay
were reproduced and fixed. These are source results, not proof of a completed
v1/v2 API, database, browser or whole-platform matrix.

Protected-branch checks, the package build, release-artifact checks, packaged
library hygiene and JDK 25 bytecode validation passed for the tested producer.
The actual WAR completed isolated H2 startup. Its transcript contains one
nonfatal disabled-remote-UI ERROR, but no exception or Joran/Janino failure.
The two Logback configurations and their 11 focused tests match the packaged
source. Existing focused tests were not rerun for publication; the final 23
offline gate cases retain their original tool-result references, not a complete
saved stdout log. WSL runtime was not exercised.

Matching Server assembly and live acceptance remain separate. C-05 remains
failed on the published 0.183.337/1.6.521 pairing until the new assembled runtime
is accepted. This release does not establish C-03 or full-matrix acceptance.

## Upgrade and rollback

No schema migration is introduced. Preserve the Server's existing settings,
volumes, authentication and network configuration. Rolling back to Engine
0.183.337 restores the scoped security-management escape path; it is not a
security-equivalent rollback for users of custom Keys.
