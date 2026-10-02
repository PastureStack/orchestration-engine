# Orchestration Engine 0.183.329

## Behavior

Readonly and restricted project roles could read a Receiver's stored capability
through GenericObject even though the Receiver API omitted its execution URL.
The QA reproduction on Server 1.6.497 / Engine 0.183.328 confirmed an exact v1
GET returned a nonempty key and a URL matching the owner's capability hash.
The execution endpoint was not called.

The fix uses the existing authorization boundary: the two low-role schema
overlays deny `genericObject.key` and `genericObject.resourceData`. This also
applies to inherited storage fields. It does not hardcode a plugin kind, parse
URLs, change hook execution, or introduce another secret-storage system.
The frozen v1 readonly/restricted schemas receive the same field denials.

Resource metadata stays readable. Receiver configuration remains available
through its typed plugin API, with execution URLs still hidden for low roles.
Owner/member/service clients retain plugin storage and normal Receiver writes.
Consumers that directly read opaque GenericObject data with a low-role token
must use the authorized typed API instead. Project/identity authorization,
MFA, cookie/session ownership, and unrelated write policy are unchanged.

## Focused evidence

- Before the fix: 4 overlay tests, 2 expected failures, 0 errors/skips.
- After the fix: all 4 overlay/response-projection tests and 2 actual frozen-v1
  tests pass with Maven 3.9.16 / JDK 25.0.4.
- The offline producer checks persisted schema equality: only key/resourceData
  are removed from GenericObject and its inherited Register schema in each
  low-role snapshot; unrelated schema/method/action/filter/field data is retained.
- Owner/member/project/service frozen schemas retain both fields.
- The package gate verifies the reviewed JSON denials and exact frozen role
  bytes inside the actual WAR, preventing a JSON-only or stale-v1 publication.
- The offline snapshot producer rejects path arguments and unreviewed input
  hashes, and uses a bounded exact-class deserialization filter. The filter
  accepts both current role snapshots and rejects an unexpected graph before
  its deserialization callback executes. An initial CodeQL candidate detected
  the former command-line-path/unfiltered producer; that failed run is retained,
  not treated as a passing gate.

## Release status and recovery

The official numeric [release `v0.183.329`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.329)
is published. Its signed annotated tag points to CI source
`14e3f0919a33282ff3026c364182f715f5dda634`; tag object
`58759af7537a03d10e16331a69998960ac676c82` is retained.
[PR #66](https://github.com/PastureStack/orchestration-engine/pull/66) was normally
squash-merged as `b4613763a731398ee091f388ef5c9cd5b88aec9d`, with the same source
tree. The release tag remains bound to the original CI source, not the squash
commit; historical `v0.183.328` provenance is unchanged.

The actual CI `cattle.jar` WAR is `87,690,873` bytes with SHA-256
`9f8e5736898b6d9c51cb96d5f7cdbe8e3831a29ce296909108f5f2d5a68f7ec4`.
[Build/security run `36951856238`](https://github.com/PastureStack/orchestration-engine/actions/runs/36951856238)
passed 266 suites / 1,123 tests, with zero failures, errors or skips, including
the six new overlay/response and frozen-v1 cases. The actual WAR package checks
confirmed both low-role JSON denials and the two reviewed frozen-schema hashes.
Artifact and build-image Critical/High findings are zero. Successful
[CodeQL run `36951859325`](https://github.com/PastureStack/orchestration-engine/actions/runs/36951859325)
retains four lower-severity findings and zero Critical/High findings, not an
all-findings-zero result.

Release readback recorded all six exact CI assets. The checksum, source-revision
and unit-test-results contents were independently read back anonymously; the
published WAR metadata matched the exact CI bytes and hash, but its public
content was not downloaded again. That exact CI WAR passed standalone startup
against isolated H2 on JDK `25.0.3`, with exit 0, no network, exposed ports or
platform-data mounts. These component gates do not accept a Server deployment,
full MariaDB integration or the whole permission matrix. Server consumption
and real v1/v2-beta role/browser results remain pending and must be recorded
separately.

There is no database migration and no HAProxy/runtime patch. A rollback to the
previous image restores the previous schema visibility, including this
capability-read weakness; preserve existing configuration and named volumes.
