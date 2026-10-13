# Orchestration Engine 0.183.345 - Persisted Instance Link scope

## Scope and repair

Custom API Keys could not read the existing native `instanceLink` type even
when its owned Project, Stack or exact resource scope allowed the operation.
The direct resolver lacked this typed adapter and collection SQL rejected it.

The adapter now derives scope from the persisted source Instance, not a client
pointer or the destination Instance. Direct authorization and collection SQL
share live, same-account Project and Stack checks and reuse container ancestry,
including managed Service and exact upgrade relationships. Scope and explicit
denials are applied before pagination. Missing, removed, foreign or contradictory
parents fail closed, including with an open default or an exact Link ID.

Reading a Link does not grant ordinary read access to either Instance. Updating
its destination retains the independent live reference check. Owner RBAC,
explicit deny, expiry, full/legacy behavior, frozen schemas and prior exact
operation-operand handling remain unchanged. No database migration, new resource
type, role grant, plugin bypass or runtime patch is introduced.

## Qualification and compatibility

15 unique focused JDK25 Link source methods passed through retained original
observations and targeted reruns after test-fixture stub corrections. This is
not a claim that one final XML file contains all 15 results. Retained observations
require independent source review before publication. Unrelated passing suites
were not replayed; captured SQL is not native database evidence.

Required normal PR checks, the original release packager and artifact gates,
immutable component publication and assembled-Server native acceptance remain
separate gates. This source note does not claim publication, deployment or a
complete role/resource matrix. Use the actual Server component manifest for the
published Engine/Web pairing and preserve configuration and volumes.

Returning to 344 restores the custom-Key Instance Link undergrant. Historical
release notes keep their original evidence and scope.
