# Orchestration Engine 0.183.355

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

This candidate supplies the Engine side of seven service tools. A compatible
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

Focused source tests cover purpose/revision gates, human confirmation,
conditional description updates, exact project scope, metadata-only audit,
and live/retired lifecycle authorization. The final r10 cleanup candidate has
150 passing focused cases and packaged class/source checks. Its final native
upgrade acceptance is still pending: earlier QA findings and partial passes
are not a complete r10 runtime pass.

CodeQL verification and the security release gate must pass for this exact
candidate. The latter builds/tests the full Maven reactor, verifies packaged
runtime boundaries, records dependency/SBOM evidence and scans source, the
Engine artifact and its builder. Findings with an available fix, High/Critical
findings and unreviewed findings block publication; only the existing exact documented
unfixed Low/Medium policy may apply. A published Engine artifact and an
assembled Server each need their own identity and acceptance evidence.
