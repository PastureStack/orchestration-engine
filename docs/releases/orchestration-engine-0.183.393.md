# Orchestration Engine 0.183.393

Ships the immutable maintained Hazelcast 5.7.7 artifact with official org.json
20260719 for CVE-2026-59171. Installation verifies the artifact hash and the
relocated JSON metadata. Cluster protocol 5.7.3, Jackson pins, Engine Java
code, 1396 API tuples, role schemas, session binding and lifecycle behavior
remain unchanged.

Both builders retain JDK 25.0.5+8 and its SHA256, with an official release-URL
fallback for the identical archive. Maven download tests isolate their own
block; transport failures and corrupt downloads still fail closed.

Original CI: 488 suites, 4341 tests, zero errors/failures, one inherited skip;
all 528 mandatory methods passed. All 94 protected class families and 183
native build-package rows match the published baseline. The artifact scan has
zero findings. Effective Dapper findings are 41 Medium / 5 Low with no official
fix; original raw reports and exact scoped policy evidence remain available.

Producer: `43b61b91723fff8e75b4ece7d75e2d81c0b9c85d`.
Signed same-tree tag target: `c2ed2651512d7536efeb09ed2d58572ff6da484b`.
Original cattle.jar SHA256: `10b51ab32d1c6f03e63bcc86fc55112b38b0c248b691d319247e02b18436328e`.
All 12 assets passed immutable attestation and anonymous byte readback.

This component release does not establish Server deployment or native browser
acceptance. Roll back through the matching Server release; preserve its data,
runtime configuration and backup. Do not replace a running JAR in place.
