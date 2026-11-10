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
