# Orchestration Engine 0.183.344 - Exact operation operand lifecycle

## Root cause and shared repair

A Key with export permission but no read permission could pass request
authorization yet fail with 404 before a matched named-link handler ran. The
manager loaded the parent resource using an ordinary read predicate. Registered
POST actions had the same problem in the action-availability validator.

The shared manager/validator now identifies only its exact operation operand.
The framework binds this purpose to the same request, method, action/link,
resource type and decoded ID; the authorization module resolves the registered
business operation and applies its existing exact-ID scope. A named handler
does not register a new capability. Unknown or ordinary relationship links stay
read-scoped. Ordinary GETs, lists and input references cannot inherit the
operation operand's permission. Owner RBAC, schema-class validation, explicit
deny, expiry and revocation remain in force.

No plugin-specific bypass, new role grant, database migration or runtime patch
is introduced. The prior actual-method and queued-operation checks remain
unchanged, as do full/legacy Key and normal browser-session authorization.

## Qualification

28 related source methods passed: 10 request-purpose/marker checks, 12 named
operation/validator pipeline checks and six mutation/legacy adjacency checks.
Other already-passing suites were not replayed. The JDBC fixture captures the
real SQL guard path without executing a database; these counts are not native
HTTP, all-role or full-matrix results.

Focused source tests, normally required PR checks, original artifact packaging
and immutable component readback are separate gates. Their actual results are
recorded by the release producer; this source note does not claim a deployed
Server or a complete role/resource matrix. Native paired v1/v2 link, action,
mutation and audit acceptance belongs to the assembled Server release.

Use the complete compatible Server image and preserve configuration and volumes.
Returning to 343 restores the known export/action pre-read undergrant. Returning
to 342 additionally restores the decorated-method classification gap; neither
is equivalent to this authorization repair. Historical component notes remain
unchanged and are not relabeled as new native acceptance.
