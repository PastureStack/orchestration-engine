# Orchestration Engine 0.183.392

Machine Driver lifecycle handlers wait for external downloads or agent replies.
They now use the existing blocking process executor by default. Three waiting
driver reactivations therefore cannot occupy all three control executor workers
and delay unrelated Stack recovery after a Server restart.

The setting is `process.machinedriver.blocking=true`. Explicit deployment
overrides remain supported. Executor sizes, native process identities, resource
authorization, and Stack/Service request payloads retain their existing behavior.

Two deterministic tests exercise actual dispatch with barriers, including the
explicit override. Original CI passed488 suites /4341 tests with zero failures
or errors and one inherited skip; all528 mandatory identities passed. The
original packaged JAR contains this exact default. Matching Server recovery
acceptance remains separate and pending.

## Human confirmation of API Key lifecycle plans

Lifecycle confirmation now selects the snapshot from the persisted plan's Key/session binding. A Cookie used for human confirmation no longer adds a Cookie-only witness to an API Key plan and incorrectly reports an unchanged target as stale. The common path covers Registry, Stack, load balancer, external/DNS service and container lifecycle operations. Cookie plans retain their witness. Live role/Key checks, changed-target rejection, human-only confirmation and at-most-once dispatch are unchanged. Three focused transport/binding tests and the nine-test adjacent Registry suite passed; deployed acceptance is still pending.

## Build environment security

The Dapper image upgrades its existing rust-coreutils package to the official Ubuntu26.04 backport `0.10.0-1ubuntu2~26.04.1`, pinned by SHA256. Existing providers and alternatives remain selected. Sixteen findings with an available fixed version are subject to the unchanged release gate. Unfixed util-linux/expat findings and four remaining rust-coreutils findings are tracked by exact package/version/PURL, limited to Dapper and reviewed again by2026-11-17. Upstream severity differences remain disclosed; no finding is marked fixed solely by registration. The product artifact is scanned independently.
