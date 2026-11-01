# Engine 0.183.366 candidate

This candidate is not published. Required original component CI/CodeQL,
complete artifact qualification and matching Web Console/Server native
acceptance remain pending. The published baseline is Engine 0.183.365.

## Service creation

The human-reviewed `service.create` parameters add `ports` and `healthCheck`:

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

## Focused evidence

60 unique focused cases passed with JDK 25 and Maven 3.9.16. They cover the
native parser/preflight path, post-preview conflicts and revocation before
claim, bounded input, actual readback, UUID rebinding, uncertain operations,
execution-result separation and IPv6 formatting. Three test-fixture setup
corrections were rerun alone; unaffected PASS evidence was retained. These
source results do not establish full CI, published artifact or native workflow
acceptance, and no final CI total or artifact digest is claimed.
