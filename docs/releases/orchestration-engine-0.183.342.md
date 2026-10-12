# Orchestration Engine 0.183.342

## Operation-specific resource lookup

Custom default-deny Keys granting only update or delete passed admission, but
the manager's operand lookup incorrectly applied the read SQL predicate. An
existing, authorized Stack therefore returned 404 before mutation. The same
lookup also serves registered resource actions.

The shared manager now carries an immutable, request-bound lookup purpose to
the SQL guard. Only the exact resource ID and type use the registered operation;
the guard also ANDs its exact decoded row ID. Derived schemas must prove their
persisted record class. Public reads, collections, references and other queries
retain read scoping. Other access plugins keep their existing default hook.

Owner RBAC, explicit denial, expiry, policy revision, audit, and full/legacy Key
behavior remain in force. Action lookups retain their original empty options,
without adopting caller pagination or includes. No database migration, OIDC,
MFA, proxy or firewall change is required.

## Validation and deployment boundary

Focused tests exercise the shared manager lookup, request/ID/operation mismatch,
nonambient purpose, and real manager-to-Jooq SQL predicates. Source validation,
component publication and assembled Server native acceptance are separate gates.
Read the corresponding Server release record for actual image identity and live
acceptance; this component note does not claim the whole API Key matrix passed.

Deploy the complete compatible Server image rather than replacing a runtime WAR.
Preserve existing configuration and volumes. Returning to Engine 0.183.341
restores the write-only operand-lookup defect; it does not resolve it.
