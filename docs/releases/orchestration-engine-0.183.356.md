# Orchestration Engine 0.183.356

This source adds bounded AI metadata reads and typed, human-approved lifecycle
plans for Stack, Service and Container resources. It preserves the existing
service-upgrade protocol, ordinary API managers and Full/legacy Key behavior.

## Changes and boundaries

- `aiResourceView` rebuilds live caller authorization and returns whitelisted
  metadata. Internal requests preserve the servlet context required for Key
  revision checks. Process-pool metadata uses stable name-based view identities
  because the runtime pool manager does not provide database IDs.
- `aiResourceChangePlan` captures target and parent snapshots, exact typed
  permissions and bound Key revision. Human confirmation and cancellation are
  separate from Key application. One persisted claim prevents duplicate apply;
  uncertain dispatch stays unknown and cannot be replayed.
- Basic creates preserve image, command and entry point and are stopped by
  default. Advanced UI form fields are not included in this batch.
- Verified lifecycle completion uses the service-discovery module's read-only
  deployment observation, including desired scale, sidekicks and eligible global
  hosts. Scale-down and removal also wait for retired-instance attachments.
  Stack start/stop observes the original child services and managed instances.
- Both plan inboxes retain approved but unsubmitted plans, allowing the human
  to cancel them. Client capacity results have a matching audit reason enum.

Enable the new plan API only with `api.ai.resource.change.enabled=true` in
addition to the existing bridge and optional service-upgrade flags. Defaults
remain off. No generic URL, arbitrary action, borrowed credential or privilege
escalation is introduced. Raw logs and sensitive payloads are not metadata.

## Validation and publication

Focused tests cover view authorization/projection, exact domain actions, plan
ownership/revision, CAS and dispatch claims, child lifecycle and cleanup,
deployment readiness, inbox cancellation and schema/audit contracts. Original
failed fixtures are retained separately from corrected targeted reruns.
Required CI, CodeQL, package and security gates must pass before publication;
source-level results do not establish Server or browser acceptance. Exact
producer commit, immutable artifact checksum and executed checks are recorded
in the release assets rather than guessed in this source note.

## Upgrade and rollback

Liquibase adds `ai_resource_change_plan`; keep it, existing plan/policy data,
named volumes, backups and the previous Server image. Stop new clients, let
accepted operations settle, cancel unused plans and revoke dedicated bridge
Keys before downgrading. Preserve these records rather than dropping the table
or migration. Ordinary resource configuration and authorization are not reset.
See [compatibility](../../COMPATIBILITY.md) and the
[API Key rollback boundary](../api-key-policy.md).
