# Orchestration Engine 0.183.395

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

## Validation and publication

[Engine PR #210](https://github.com/PastureStack/orchestration-engine/pull/210)
merged with all seven required checks passing. The original CI produced 4,355
tests in 491 suites with zero failures, zero errors and one inherited skip.
It verified 542 mandatory identities and 95 packaged class families. The
1,396 API tuples and existing grants were preserved.

The five Ubuntu snapshot index downloads succeeded with APT signature
validation. Four Ubuntu 26.04 CVEs account for eleven newly tracked Dapper
package occurrences. Canonical lists each as awaiting a fix. These exact
builder entries are due for review on 2026-11-18. Product artifact, fixable
and high severity checks remain enforced.

The [immutable component release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.395) publishes the original CI JAR
with SHA256 `0c446a67b9a11fb9018045be0b114b328365e4c3da314d1db6e4e4421d07fcfa`. Its original build
producer is `9611a0abde9b8e13a21182b544b98cdac92b7c6a`. The signed release commit is
`b1c38752634a81c0f82461a4cf1c3d737655d99d`. Matching Server deployment and Host scheduling
acceptance are tracked in the Server release evidence.
