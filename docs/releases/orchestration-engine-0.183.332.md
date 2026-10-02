# Orchestration Engine 0.183.332 — candidate

## Root cause and change

The Web Console unallocated-volume list requires an explicit `isNative=false`
classification. The current API role overlay removes `isNative`, and frozen v1
role schemas also omit it. An absent flag cannot safely be interpreted as false.

This candidate adds `volume.isNative:r` to the existing user authorization
overlay and narrowly supplements the missing field in `FileSchemaFactory`.
The field remains server-owned and read-only. Existing Volume methods, actions,
other fields, account scoping and role restrictions are not changed. Stored
true/false values remain authoritative; the existing nonnullable boolean default
is applied only by the server's declared schema. No frontend fallback, data
migration, runtime patch or proxy change is introduced.

## Verification completed

The targeted Maven reactor passed 18 tests with zero failures, errors or skips,
including six new tests covering:

- Packaged v1 owner, member, readonly, restricted, user and admin schemas;
  only the missing native field is added, preserving other field serialization,
  methods and complete resource/collection action serialization.
- Shipped v2 user/admin/project/owner/readonly/restricted overlays, with no
  create/update permission on the native flag, including the actual readonly
  `Project → NotWritable → read-user` sequence with unchanged GET-only methods.
- Formatter preservation of true/false, the declared server default and no
  undeclared boolean leakage.
- Client POST/PUT attempts to change classification: client values are excluded;
  creation uses only the server default.

The release workflow additionally requires these named regressions to pass.

## Pending acceptance

This is not yet a published artifact or a complete Volume lifecycle pass.
Formal immutable component readback, Server packaging and actual browser
create/list/edit/cancel/reload/delete and role denials remain required.

## Upgrade and rollback

No persisted data or role membership migration is needed. Preserve existing
deployment environment, mounts, restart policy and authentication configuration.
Use an immutable newly published Server image only after the component gates
pass; do not overwrite previous tags. Rolling back the component restores the
old missing-field response contract and can hide unallocated volumes again.
