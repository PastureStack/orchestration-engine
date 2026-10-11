# Engine 0.183.338 release candidate

This source candidate is not a published Server image. Engine 0.183.337 with
Server v1.6.521 remains the current published pairing until release verification
and assembly complete.

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

## Verification status

Focused source verification covers 24 governance cases, 19 authorization cases,
11 policy-management cases and two v1 read-contract cases. The 69 target-resolver
cases passed before the later account-only review fixes and remain applicable.
The independent review's numeric-ID regression and stale-full-DTO authority replay
were reproduced and fixed. These are source results, not proof of a completed
v1/v2 API, database, browser or whole-platform matrix.

Release requires the protected-branch checks, verified component packaging and
the matching Server assembly. The live C-05 matrix case remains failed on the
published 0.183.337/1.6.521 pairing until the new assembled runtime is accepted.

## Upgrade and rollback

No schema migration is introduced. Preserve the Server's existing settings,
volumes, authentication and network configuration. Rolling back to Engine
0.183.337 restores the scoped security-management escape path; it is not a
security-equivalent rollback for users of custom Keys.
