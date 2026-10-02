# Orchestration Engine 0.183.332

## Root cause and change

The Web Console unallocated-volume list requires an explicit `isNative=false`
classification. In `0.183.331`, the API role overlay removed `isNative`, and
frozen v1 role schemas also omitted it. An absent flag cannot safely be
interpreted as false.

This release adds `volume.isNative:r` to the existing user authorization
overlay and narrowly supplements the missing field in `FileSchemaFactory`.
The field remains server-owned and read-only. Existing Volume methods, actions,
other fields, account scoping and role restrictions are not changed. Stored
true/false values remain authoritative; the existing nonnullable boolean default
is applied only by the server's declared schema. No frontend fallback, data
migration, runtime patch or proxy change is introduced.

## Publication and verification completed

The signed annotated [numeric release `v0.183.332`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.332)
binds exact built source `7a625eee58fb2bdba83d2f008bdf7dd3c0ae4295`.
Its `cattle.jar` WAR is `87,700,267` bytes, SHA256
`31090699e214f8e357f7fe413ce307e722b9b53177003e5de0bbbca1bc7bc3f5`.
[Build/security run 37069556952](https://github.com/PastureStack/orchestration-engine/actions/runs/37069556952)
passed 270 suites / 1,150 tests with zero failures, errors or skips, including
the six new Volume regressions covering:

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

Official release readback verified exactly six CI-derived assets and three
anonymous content readbacks (checksum, source revision and unit-test results).
The checksum's only transformation is the verified filename change from
`dist/artifacts/cattle.jar` to portable `cattle.jar`; both contain the exact WAR
SHA256 and one LF-terminated line. Summary and unit evidence bytes remain exact
CI bytes. The public WAR was not downloaded again.

The exact WAR completed standalone JDK `25.0.3` / H2 startup with exit 0,
network `none`, no exposed ports and no platform-data mounts. These component
checks do not establish Server deployment or native browser acceptance.

## Pending acceptance

Server `v1.6.505` is not yet published. Server packaging/deployment and actual
browser create/list/edit/cancel/reload/delete and role denials remain required.
The full resource/role matrix remains **INCOMPLETE**; formal component
publication and isolated startup do not turn those pending checks into PASS.

## Upgrade and rollback

No persisted data or role membership migration is needed. Preserve existing
deployment environment, mounts, restart policy and authentication configuration.
Use an immutable newly published Server image only after the component gates
pass; do not overwrite previous tags. Rolling back the component restores the
old missing-field response contract and can hide unallocated volumes again.
