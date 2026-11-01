# Engine 0.183.366

[Engine 0.183.366](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.366)
is published. Required original component CI/CodeQL,
artifact qualification and anonymous readback of all 12 original assets passed.
Matching assembled Server/native business acceptance remains pending.

## Service creation

The human-reviewed `service.create` parameters add `ports` and `healthCheck`:

The example is an internal Engine plan payload. WebMCP/UI must first use
`find_resources` to obtain an opaque `parentRef`, which the trusted adapter
resolves; users and models do not supply raw resource IDs such as `stackId`.

```json
{
  "name": "web",
  "image": "nginx:stable",
  "stackId": "1st3",
  "ports": ["18080:80/tcp", "53/udp"],
  "healthCheck": {"port": 80, "interval": 2000, "healthyThreshold": 2}
}
```

`ports` contains at most 64 native `PortSpec` strings, each at most 128
characters. Fixed host-port mappings and native allocation for omitted host
ports are both supported. Preview, human confirmation and apply before the
durable dispatch claim reuse the native parser and port preflight. Native
validation codes are retained, and the final filtered manager remains the
last validation boundary.

The closed health-check subset requires integer `port` in 1..65535. Optional
`interval` and `responseTimeout` are milliseconds; these and `healthyThreshold`
and `unhealthyThreshold` require integers in 1..2147483647. Optional
`initializingTimeout` and `reinitializingTimeout` accept 0..2147483647
milliseconds. Optional `requestLine` is at most 1024 characters without NUL,
CR or LF. `strategy` accepts `none` or `recreate`; omission uses the native
`recreate` default. Other optional numeric fields retain omission rather than
inventing defaults. Health-check name and quorum strategy configuration are
outside this subset; unknown fields and incorrect types are rejected.

The parameters map to native `launchConfig` fields with bridge networking and
`startOnCreate=false`. Creation remains inactive. A later start requires its
own human-approved plan; metadata updates remain limited to name/description.
Private human entry of environment-variable values is not part of this batch.
Neither `environment` nor raw `launchConfig` is accepted as plan input.

## Catalog native projection correction

Catalog's real Docker transformer stores dynamic values such as `imageUuid`,
`environment`, `ports` and `logConfig` in `InstanceRecord.data.fields`. The
Materializer previously omitted that map when wrapping the transformed
instance, so the native role-schema projection lost required business values.
It now passes a fresh copy of `DataUtils.getFields(instance)` to the existing
`WrappedResource` projection. The current role schema, ID formatter, `POST`
method and non-create-response handling remain unchanged.

Direct tests use the real transformer and native base/project-role schemas to
verify the exact image, environment value with an embedded equals sign, port
mapping, log driver/configuration and bean-backed name. Narrowed schemas,
read-on-create-only fields and unlisted dynamic fields still restrict output,
and the source dynamic map retains its business values. This corrects the
existing Catalog projection; it adds no permission or new environment-input
channel. Artifact qualification passed; matching native workflow acceptance
remains pending.

## Verified results and compatibility

Completion verifies the actual created Service identity/UUID, owner, parent,
image, scale, programs, network mode, ports and health check. Fixed port
mappings must match. Native allocated mappings must preserve private port,
protocol, bind address and multiplicity, with valid assigned host ports.
Missing, changed or conflicting mappings cannot establish completion.

Only a succeeded, verified receipt exposes read-only
`executionResult.assignedPorts`. This reports native persisted values without
changing the approved `changes` or treating an observed assignment as new
consent. An uncertain dispatch or receipt write retains its durable claim and
is never automatically replayed. Current role, project, Key revision/expiry,
explicit DENY and audit requirements remain in force.

Native `PortSpec.toSpec()` now brackets IPv6 bind addresses, preserving
roundtrip parsing for fixed and allocated mappings. Existing IPv4 and unbound
mapping output is retained. This affects native Service, load-balancer and
port-update formatting too; it does not rewrite existing database values.
Host IPv6 binding and routing still require native runtime acceptance.

The four AI opt-ins remain independently default-off. Full/legacy Key behavior,
runtime dependency coordinates and database schemas are unchanged. Clients
must understand these typed fields and the read-only execution result.
Consume a qualified component through a matching published Server release.

## Qualified component evidence

Original [Security CI 41328904546](https://github.com/PastureStack/orchestration-engine/actions/runs/41328904546),
[custom CodeQL 41328904462](https://github.com/PastureStack/orchestration-engine/actions/runs/41328904462) and
[organization CodeQL 41328907700](https://github.com/PastureStack/orchestration-engine/actions/runs/41328907700)
passed, with zero CodeQL aggregator annotations and no new alerts. The original
structured Surefire report contains **420 suites / 3,912 tests / zero failures /
zero errors / one existing skip**.

The reviewed 62-case focus comprises **21 new source methods relative to Engine 0.183.365**
(19 Service creation and two Materializer) and 41 inherited methods. Full affected
classes add one existing Materializer method, giving 63 observed methods. These
are not 62 new tests. All 97 required method identities passed; the prior 95
requirements were retained and two Materializer regressions were added. The
Materializer cases use the real transformer and native role schemas.

All 1,396 operation tuples plus the source/unknown-action row passed (1,397 rows).
Independent source re-extraction preserved every tuple, semantic and admission
disposition. The 441 production inputs have fingerprint
`7ce17c8b05ccfba961414808148d8638bf54a9ad3bbc7de00facfcba4b26ac39`.
Three explicit new role entries provide read-only access to the server-produced
execution result; no mutation grant is added. Historical producer355 and review
chains remain intact.

Eight changed production classes have original-artifact class/module hashes and
reviewed disassembly. All 16 packaged frozen `.ser` files match Engine 0.183.365
source bytes, and five frozen role-schema hashes match that published baseline.
Four packaged native JSON schemas bind the bounded result projection. The 89 POMs and four build scripts
are version-only changes. The packaged official MariaDB JDBC3.5.11, CycloneDX
SBOM and original product/raw/effective Dapper scan evidence passed the existing
security gates without an exception. No new scan is claimed during qualification.

## Published identities

| Identity | Published value |
|---|---|
| Signed producer | `3ce114486954403cead091379916c2d69c052336` |
| Normal merge and tag source | `bbba0e5a3e40ed58c98643610943586d0e521328` |
| Tested whole tree | `f9482dd3c8c0a1e24763c23009ea50ddccc2e66e` |
| Original cattle.jar SHA256 | `0fd51b38c22c495d5f245527213807fa486e3bba571756b410ef6292f424db81` |
| Original cattle.jar size | 88,245,511 bytes |

The [release metadata](https://github.com/PastureStack/orchestration-engine/releases/download/v0.183.366/engine366-release-metadata.json)
records the complete original-CI and packaged evidence. All 12 original release
assets passed anonymous SHA256 readback. The JAR was streamed from the retained
original CI ZIP; no publication rebuild or second full JAR was produced. The
workflow provides original artifact/source/tree/lock provenance and does not
provide native SLSA attestation; none is inferred.

Matching Server565 / Engine366 / Web205 / Bridge027 / Compose14.41 assembly and
designated native, Catalog private human input, role/Key/cross-ID, IPv6 and
weak-model workflows remain separate pending QA. Existing failures and unaffected
PASS evidence remain separate. Component CI and publication do not establish
complete frontend or all 243 business workflows.
