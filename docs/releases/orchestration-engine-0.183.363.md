# Engine 0.183.363

[Published as v0.183.363](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.363)
from the qualified original CI bytes. All twelve release assets passed anonymous
SHA256 readback. Matching assembled Server publication and designated live QA
remain pending.

## Changes

External Service target previews now read the actual current hostname and IP
addresses from their native dynamic fields. These fields have no ordinary Java
getters, so earlier previews could incorrectly show the before value as null.
The two-line repair preserves display sanitization, native owner and
authorization checks, and the complete-data compare-and-set snapshot.

Seven real ServiceRecord regressions cover hostname-to-IP, IP-to-hostname,
IP-to-IP, hostname changes, null/empty values, safe projection and no record
mutation. Four nonempty before-value cases reproduced the earlier failure;
all seven passed in the published producer's original CI report. The eight
Engine362 ServiceIndex cleanup regressions also passed in that report and are
retained as inherited cases, not new Engine363 tests.

MariaDB Connector/J advances from 3.5.10 to the
[official 3.5.11 patch](https://github.com/mariadb-corporation/mariadb-connector-j/releases/tag/3.5.11)
on the same 3.5 line. The managed runtime dependency and both Maven/packaged-JAR
security floors are updated together. The other 88 changed POMs and four build
scripts contain only the component version update; other runtime dependencies,
database configuration, SQL and authorization flows remain unchanged.

## Original component qualification

- [Security run 41276564016](https://github.com/PastureStack/orchestration-engine/actions/runs/41276564016),
  attempt 1, passed. The structured report contains **3,858 tests in 414 suites**,
  zero failures/errors and one existing skip.
- [Custom CodeQL 41276563988](https://github.com/PastureStack/orchestration-engine/actions/runs/41276563988)
  and [organization CodeQL 41276564757](https://github.com/PastureStack/orchestration-engine/actions/runs/41276564757)
  passed. The alert aggregator reports no new alerts and zero annotations.
- All 1,396 reviewed operation tuples plus the source/unknown-action case passed
  (1,397 projection rows). The 431 current source inputs have fingerprint
  `5bd56f773b6d57827ee04cfb5fb51f3e9ff95969a152edbbbff40abf3bb338fc`.
  Tuple/semantic delta from Engine362 is zero; producer355 and the complete
  previous-review chain are preserved. No HTTP route or grant is added.
- The original packaged `AiResourceChangeDomain.changes` bytecode contains both
  reviewed dynamic-field reads. The JDBC JAR, Maven CycloneDX SBOM and current
  scanner package inventory identify **3.5.11**, with no packaged 3.5.10.

CVE-2026-119210, CVE-2026-119276, CVE-2026-119281 and CVE-2026-119280 are absent
from the original product and raw/effective Dapper reports, including ignored
entries. The required release gate passed without adding a vulnerability
exception or suppression. The packaged JDBC JAR matches the verified official
793,387-byte artifact, SHA256
`42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4`.
This component evidence does not replace real database compatibility acceptance.

## Immutable publication identity

| Item | Verified identity |
|---|---|
| Producer | `6b23ada41c0324d708739433b68f5ff00d2209c3` |
| Normal merge and tag source | `0173d171053fd9d232d59ebe2adbf4c91f132ccf` |
| Tested and merged tree | `4a0f382ec4371e0343060cc567103e5611fe5430` |
| Original `cattle.jar` | 88,234,250 bytes; SHA256 `b2c1beca47f0654c22633012359eb8171202cb8192f7590ceddb02289875deda` |
| Original CI artifact | ID `13072150052`; complete ZIP SHA256 `a9443947a3c8840d0c123186d89822023094a08d841363de3afd79e92884c043` |

The producer and normal merge have the same tree. Publication used the original
artifact without a rebuild. The twelve public assets retain the packaged-JAR,
role-schema, SBOM and raw/effective scanner evidence; the complete original CI
ZIP remains local. The
[published metadata](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.363/engine363-release-metadata.json)
records their hashes. Provenance is GitHub artifact/source/tree evidence; this
workflow does not provide a native SLSA attestation.

## Runtime and compatibility boundary

The ServiceIndex cleanup owner, Certificate metadata, typed audit, Container
network cleanup and session protections are retained. Ordinary Full/legacy
behavior, current actor/project role, Key scope/revision/expiry/revocation and
explicit DENY checks remain required. A human cookie session still approves
the exact plan; a Key cannot approve or cancel its own plan. The four independent
AI opt-ins remain default-off.

Matching assembled Server publication and JDBC 3.5.11 database compatibility
remain pending, as do native/browser External Service before/after review,
applicable role/cross-ID layouts and designated weak-model workflows.
ServiceIndex cleanup remains limited to its verified native owners. These
component results do not complete frontend acceptance. Documentation commits
do not replace the producer, tag source or original artifact.
