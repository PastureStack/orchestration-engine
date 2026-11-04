# Engine 0.183.368 candidate

This is an unreleased source candidate. Original CI, packaged artifacts,
publication, matching client review and Server/native runtime acceptance remain
pending. README continues to describe the published Engine 0.183.367.

## External Service CREATE TCP health checks

The existing typed External Service CREATE parameters accept an optional closed
`healthCheck` object. It contains only caller-supplied fields:

| Field | Typed boundary |
|---|---|
| `port` | Required integer from 1 through 65535 |
| `interval` | Optional positive integer through 2147483647 |
| `responseTimeout` | Optional positive integer through 2147483647 |
| `healthyThreshold` | Optional positive integer through 2147483647 |
| `unhealthyThreshold` | Optional positive integer through 2147483647 |

The timing limits describe the existing AI typed positive-integer subset. Native
metadata declares no timing minimum, maximum or default. Omitted values are not
filled. Unknown fields, nulls, wrong types, fractional values and caller HTTP
controls such as `requestLine`, URL, Host or strategy are rejected.

The approved changes retain exactly those caller fields. Only a detached native
body receives `requestLine=null`; this private TCP marker is not added to the
public parameters or review summary. After normal native schema validation,
removing timing fields or introducing HTTP fallback is rejected before prepare
or dispatch. A verified create receipt requires the same TCP values read back
from the actual External Service. Omission preserves None.

External Service health checks remain native create/read fields; this change
does not add UPDATE support. Ordinary Service health checks, Full/legacy
behavior, current role/Key and parent authority, policy revision, normal human
approval and a single durable claim remain unchanged. No new HTTP route,
operation or grant is introduced.

## Source evidence and remaining qualification

The existing extractor independently reproduced all 1396 HTTP tuples and 447
source inputs against published Engine 0.183.367. Only the three External TCP owners changed
their source hashes. Operation semantics, admission dispositions, native schemas,
role grants and frozen serialized schema bytes remain unchanged; historical
producer and review records are retained in the contract inventory.

Existing scoped source evidence covers 48 unique methods, with 7 new TCP methods
and 41 inherited methods. The required-method registry preserves all prior 125
identities and adds those exact 7 for future original-CI XML verification. These
local results were carried, not rerun during version preparation. No full Maven
build, dependency download, artifact qualification, publication or runtime PASS
is claimed by this candidate preparation.
