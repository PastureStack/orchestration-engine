# Orchestration Engine 0.183.334

## API Key policy and audit contract

Keys may use full, closed or custom access and an optional expiration timestamp.
Full and untouched legacy Keys preserve their existing account/project boundary
and live RBAC; policy metadata is not a role snapshot or a permission grant.
Custom policy rules use explicit operation IDs and persisted ancestry. A deny
takes precedence over an allow. Both v1 and v2-beta use the same enforcement;
collection constraints are applied before pagination, with object and attachment
checks at the shared response boundary.
Before creating or updating a resource, destination parents and reference checks
follow the actual input schema and its create/update flags. A read-only
`serviceId`, unknown `instanceId`, or parent-like string cannot grant Stack-scoped
creation authority for a field that validation discards. Writable `stackId`
continues to support directly Stack-owned containers; a service relationship is
not required when the container has a verified persisted Stack parent.

Policy updates use an exact revision/data/state compare-and-swap. A broader
policy or longer validity requires the existing single-use MFA confirmation,
bound to the operator, purpose and server-canonical request digest. Client input
cannot replace policy ownership, stored revision or raw credential data.

Verified Key requests durably record a decision before side effects, then record
the actual response or terminal background/agent outcome separately. A 202
response or stream ticket is not a successful business operation. Bounded durable
outboxes retry delivery without repeating business actions. Audit failures are
reported as `AuditUnavailable`, not as a fabricated permission denial.

Audit records contain safe identifiers and status metadata, not Authorization,
Cookie, JWT, OTP, OIDC code, key secrets, stream tickets or sensitive payloads.
Invalid secrets have anonymous attribution; a supplied public Key ID alone is
never proof of the caller's identity.

## Integration and compatibility

Delegated operations require host-api 0.38.5, websocket-proxy 0.23.15 and Linux
node-agent 0.13.28. The existing full bootstrap image remains 1.2.31; the Server
distributes the independently versioned Node and Host archives through its
authenticated config-item path. Node owns process lifecycle and launches the
installed Host producer; it does not duplicate Host authorization or auditing.
An older backend cannot accept verified Key streams without the required audit
capability, while ordinary non-Key sessions retain their compatibility path. Scoped
tickets are bound to the Key, policy revision, operation, resource and host;
authorization is checked again during the stream. Legacy full-Key authorization
and ordinary non-Key sessions retain their prior behavior. The audit path for a
verified Key still requires durable admission.

No authentication provider, MFA, CSRF, HTTPS cookie or host firewall setting is
disabled. Policy data lives in credential metadata; this release adds no database
schema migration. Deploy through the matching complete Server image, not by
replacing files in a running container.

## Acceptance and rollback

Focused unit results and the exact packaged source/artifact identity are recorded
in release assets. Full role/API/UI acceptance is a separate Server-level matrix;
source tests alone do not establish that matrix.

Before changing a Server image, retain its Compose/runtime contract and database
backup. Keep the previous immutable image. A rollback must restore matching
component versions; older components cannot interpret the new restricted-Key
contract. Do not convert restricted Keys to unrestricted legacy Keys as a
rollback workaround. Existing unrelated data and volumes must be preserved.
