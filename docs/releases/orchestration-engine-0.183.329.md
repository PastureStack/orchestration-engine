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

The source is prepared for the normal immutable component build/publication.
Artifact hash, source revision, CI evidence, Server consumption, and real
v1/v2-beta role/browser results will be recorded after those gates complete.
No full permission-matrix acceptance is implied by these focused tests.

There is no database migration and no HAProxy/runtime patch. A rollback to the
previous image restores the previous schema visibility, including this
capability-read weakness; preserve existing configuration and named volumes.
