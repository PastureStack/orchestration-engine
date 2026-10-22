# Orchestration Engine 0.183.353

## Refresh endpoint identity after a service upgrade

A service upgrade can replace an instance while keeping its published IP
address and port. Endpoint reconciliation previously treated that address and
port as the complete identity, leaving the old instance reference in persisted
`publicEndpoints`. A later service edit could therefore fail with
`InvalidReference` after the old instance had been removed.

Reconciliation now compares an unordered set of the five endpoint fields:
`ipAddress`, `port`, `serviceId`, `hostId` and `instanceId`. A changed instance,
host or service reference persists the fresh endpoints and emits the existing
change notification. Reordering an identical set does not write or notify.
Missing, null and empty endpoint lists are equivalent; clearing an existing
list persists an empty list once.

The identity is local to endpoint reconciliation. The shared `PublicEndpoint`
DTO equality contract is unchanged, as are reference authorization and the
endpoint query's exclusion of removed instances. There is no database migration,
API schema change or direct rewrite of existing endpoint records. Normal
reconciliation repairs the endpoint data.

The image build also retrieves the existing CA bootstrap package from the
locked Ubuntu snapshot after that older package was removed from the live
archive. Its version and SHA256 remain unchanged.

## Validation boundary

Focused regressions exercise `updateObjectEndPoints` using independently stored
JSON and fresh readback, including replacement at an unchanged address, host and
service changes, unordered equality, duplicate identities and null/empty data.
Source tests, complete CI, published artifacts and an assembled Server upgrade
remain separate validation results. In particular, the focused JSON persistence
fixture is not a live database or end-to-end service-upgrade test.

Retain the previous Server image and persistent volumes for code rollback.
