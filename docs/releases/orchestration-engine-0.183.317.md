# Orchestration Engine 0.183.317

- Reconcile eligible local and external accounts into the single shared
  Default environment identified by the stable `adminProject` UUID.
- Use the stable internal account identity for the baseline `member` entry so
  provider claim formatting cannot create duplicate environment membership.
- Preserve every existing direct or group role, including `noaccess`; shared
  provisioning never replaces an explicit authorization decision.
- Serialize exact membership creation with the existing project lock and make
  repeated or concurrent login reconciliation idempotent.
- Keep existing personal environments and workloads intact. Accounts created
  by an older release adopt the shared Default on their next successful login.
- Retain configurable `personal` and `none` provisioning modes for compatible
  deployments.

The focused auth-logic tests cover baseline creation, group-role preservation,
direct no-access preservation, and a missing shared project that fails closed.
The runtime matrix must prove owner, member, restricted, read-only, and
no-access behavior through both v1 and v2 APIs, exact stack visibility, denied
direct UI routes, and six independent OpenID Connect browser sessions before a
Server release consumes this Engine.
