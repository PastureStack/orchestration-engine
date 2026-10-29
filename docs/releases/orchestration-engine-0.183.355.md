# Orchestration Engine 0.183.355

## Published artifact

[Release v0.183.355](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.355)
contains the original tested CI artifact, with no publication rebuild.

- Original producer: `9ea138d1db1718cf65157c9564c379e62bafe9a0`.
- Signed merged release source: `a8f23bf234199ac39c7b14713dab9a7735f29cbe`.
- Identical source tree: `a4e726b6bfc05f73b0fa997d6143212c66e8036f`.
- `cattle.jar`: 87,981,315 bytes; SHA-256
  `a7f651f84a9fe6deef2c9f66f50c13c70e450e25f9dfa6fb59129b2681f9f54f`.
- [Full build/security CI](https://github.com/PastureStack/orchestration-engine/actions/runs/40855249595)
  passed 363 suites and 3,469 cases, with zero failures/errors. The existing
  non-POSIX storage case is skipped on Linux; its POSIX control passes.
- [CodeQL verification](https://github.com/PastureStack/orchestration-engine/actions/runs/40855249449)
  and required PR checks passed; the separate alerts check reported no new alerts.

Source secrets, product and effective builder High/Critical findings, and
findings with an available fix are zero. Raw scans, effective scans, SBOM,
exact vendor-source proof and OpenVEX are retained separately as release assets.
Anonymous metadata, checksum, producer and full-JAR download readback matched.

## Guava security patch

The existing Guava dependency advances from `33.7.1-jre` to official
[`33.7.2-jre`](https://github.com/google/guava/releases/tag/v33.7.2) for
[CVE-2026-102554](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94).
The upstream patch bounds initial allocation when deserializing compact
maps/sets and MapMaker maps. POM and packaged-JAR checks require at least this
patch on the existing stable 33.x JRE line. Focused tests exercise oversized
serialized counts, linked collection variants, ordinary round trips and the
Guava cache operations used by schema authorization. The Java 25 baseline and
platform API contracts are unchanged.

The Maven 3.9.16 build runtime uses the same official Guava patch, and the
existing Ubuntu Brotli package receives the ABI-compatible
`1.2.0-3ubuntu0.1` fix from USN-8956-1. Downloads and installed versions are
verified. Pip remains byte-identical: the new msgpack/setuptools scan findings
refer to native unpacker and legacy wheel-install code absent from its exact
vendored slice. Raw findings remain available alongside the narrow VEX proof;
full installations, unknown paths or changed vendor bytes do not inherit it.

## WebMCP v1.3 backend contracts

This release supplies the Engine side of seven service tools. A compatible
Web Console or stdio bridge exposes the tool protocol; this repository does
not install those companion components.

| Tool | Engine contract |
| --- | --- |
| `find_stacks` | Read only the caller's authorized Stacks. |
| `find_services` | Read only authorized Services and preserve exact resource identity. |
| `get_service` | Return current Service details under the same role and Key scope. |
| `update_service_description` | Compare the expected description and update only that field. |
| `preview_service_upgrade` | Create a private, expiring plan bound to the actor, project, Service snapshot and originating Key revision. |
| `apply_service_upgrade` | Dispatch an approved plan through the existing Service upgrade manager. |
| `get_operation` | Read the same durable plan and report observed progress or verified completion. |

The optional `purpose=ai-bridge` policy narrows an existing Key to the supported
discovery and AI endpoints; it does not grant new owner or resource authority.
`api.ai.bridge.enabled=false` and `api.ai.service.upgrade.enabled=false` remain
the defaults. Purpose and revision response metadata come from the verified
credential. Bridge requests pin that revision, and synchronous writes and
queued processes recheck revocation, expiry, revision and current owner access.
Ordinary Full and legacy Key capabilities remain unchanged.

## Conditional writes and human approval

`POST aiServiceDescriptionUpdates` accepts only the target Service,
`description` and required nullable `expectedDescription`. The current value
is checked under the existing database write transaction and row lock before
the normal Service manager writes the field. A changed value returns 409.
Empty string and null remain distinct; empty string clears the description.
Whitespace and Unicode are preserved within the 1024 UTF-16-unit limit.

Upgrade plans expire after five minutes and retain a server-owned Service
snapshot. The human reviews and confirms the plan through their cookie
session; the originating Key cannot confirm or cancel it. Applying a
Key-bound plan requires that same Key and revision. Repeated apply requests
observe the durable receipt rather than dispatching another upgrade, and an
uncertain dispatch remains unknown until authoritative observation resolves it.

The original upgrade authorization follows verified process relationships
through Instance stop/start/removal, NIC/IP, ports, mounts, OS storage and
deallocation. Live relationships use the normal resolver; retired upgrade
relationships are accepted only for the exact old Instance and its bounded
cleanup chain. Cross-project or cross-Instance references and explicit DENY
rules remain enforced. Named/shared volumes do not acquire a removal grant;
unused-volume deactivation is distinct from storage removal.

Success requires the new instances to match the requested image and retained
settings, and the relevant old instances, network/port attachments and mounts
to settle. Accepted or running work is not reported as verified success.
The existing durable audit path records allowed/denied decisions and verified
Key metadata without description contents. Client tool reports remain
`client_reported` / `NOT_EVALUATED` evidence, separate from business outcomes.

## Persistence and validation boundary

Liquibase adds the private `ai_service_upgrade_plan` table through
`core-127.xml`. This table stores plan snapshots and receipts; no general
Service-update API semantics or owner-role model is replaced. Retain policy
records, the plan table and persistent volumes when rolling back application
code. Do not delete the changelog entry or rewrite active receipts.

Source tests cover purpose/revision gates, human confirmation,
conditional description updates, exact project scope, metadata-only audit,
and live/retired lifecycle authorization. Component CI and artifact checks do
not substitute for assembled Server acceptance or a deployment readback.

CodeQL verification and the security release gate passed for the original
producer above. The latter builds/tests the full Maven reactor, verifies packaged
runtime boundaries, records dependency/SBOM evidence and scans source, the
Engine artifact and its builder. Findings with an available fix, High/Critical
findings and unreviewed findings block publication; only the existing exact documented
unfixed Low/Medium policy may apply. A published Engine artifact and an
assembled Server each need their own identity and acceptance evidence.
