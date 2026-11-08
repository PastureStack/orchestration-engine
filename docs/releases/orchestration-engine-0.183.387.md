# Orchestration Engine 0.183.387 candidate

This candidate adds three bounded WebMCP edits through the existing query,
human-preview, confirmation and execution flow:

- Reset a Host's custom name to its current native hostname without changing the
  hostname. Reset and an explicit replacement name are mutually exclusive.
- Change one existing Container public-port binding while keeping its private
  port, protocol and binding identity fixed. The native Port owner serializes
  updates with allocation and checks collisions across the physical host.
- Change one existing Container link target using an authorized target reference,
  while retaining the link's source and name.

Existing native Port clients do not need a new header. Supplying the conditional
header enables strict comparison against the current tuple. Typed producers
always require the server-owned before state; they cannot fall back to an
unconditional write. Current roles, live API Key restrictions, parent identity,
confirmation and audit remain in force.

The driver-service cleanup fix schedules the native asynchronous
deactivate/remove chain when a Network or Storage driver is still activating.
It does not widen lifecycle state gates, synchronously wait under the parent
process lock, or take over each driver's child-resource cleanup responsibility.

No database migration is introduced. Deploy only through a matching qualified
Server assembly. Preserve existing configuration, data volumes and the previous
immutable Server image for rollback.

Source-local tests are development evidence. Normal component CI, packaged
artifact qualification, immutable publication and matching Server native
acceptance are pending; this note does not claim complete frontend coverage.
