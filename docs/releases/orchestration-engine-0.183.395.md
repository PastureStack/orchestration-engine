# Orchestration Engine 0.183.395 candidate

## Scope

Host lookup accepts both the optional custom name and the native hostname.
Previously the name-only database condition excluded Hosts without a custom
name before their hostname could be considered. Host alternate-name lookup now
uses the ordinary authorized 51-row page and filters its safe projection. Its
continuation remains available even when that page has no match. The stored
name is preserved; no Host or workload is renamed, activated or deactivated.

Native actor, tenant, role and Key authorization are unchanged. Other resource
name searches retain their database predicate; Registry address lookup is
unchanged. There is no API/schema, dependency or persistence migration.

The builder uses the official ca-certificates 20260816ubuntu0.26.04.2 update
(USN-9001-1), pinned to its official package SHA256. This fixes JKS keystore
resynchronization; the superseded .1 archive could no longer be fetched
reliably. TLS validation, package metadata checks and negative controls remain.
See https://ubuntu.com/security/notices/USN-9001-1.

## Validation boundary

Four new regressions cover null/custom names, native hostname, bounded empty
pages with a later match, duplicate candidates and role/Key denial. Together
with the adjacent metadata, name search and Registry view tests, 24 focused
JDK 25 tests pass. Component CI, immutable publication and matching Server
Host scheduling acceptance remain pending.
