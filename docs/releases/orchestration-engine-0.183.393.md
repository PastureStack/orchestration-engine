# Orchestration Engine 0.183.393 candidate

Updates only the maintained Hazelcast runtime to 5.7.7 with the official
org.json 20260719 security update (CVE-2026-59171). The relocated JSON classes
must be patched in the owning runtime; adding an unshaded Engine dependency
would leave the vulnerable copy intact.

Hazelcast cluster version 5.7.3, Jackson pins, Engine Java code, role schemas,
API tuples, session binding and the Engine392 driver routing fix are retained.
The installer verifies the exact immutable runtime asset and its embedded
org.json metadata. Source/packaged artifact scans and required CodeQL checks
must pass before publication. No Server or native acceptance is claimed here.

Builds retain the official Temurin 25.0.5+8 archive and its existing SHA256.
If its GitHub asset API returns an HTTP error, both container build recipes
retry the same official archive through the release URL. A successful download
still must pass the existing hash, compiler and runtime version checks before
use. No toolchain version, native package, or application behavior changes.
The initial API-403 build failure is retained and did not run Maven tests.
