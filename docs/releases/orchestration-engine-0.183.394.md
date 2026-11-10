# Orchestration Engine 0.183.394 candidate

Registry-specific API Key grants and denials must also constrain the native
StoragePool table lookup. Previously an exact Registry grant could return an
empty result; under default allow, its explicit denial could be missed by a
parent-table query. Direct authorization also uses the persisted kind through both schema names,
so a parent alias cannot hide a policy denial from its audit decision.
The predicate now recognizes the registered native table
and requires its persisted kind to be `registry`. It keeps the original owner
restriction, exact resource ID, deny precedence and pre-pagination filtering.

Full/legacy Keys retain the existing authorization path. Other StoragePool
kinds, schema fields, API methods, database migrations, dependencies, OIDC/MFA,
human approval and Registry removal/retention are unchanged.

Nine database/direct-authorization regression tests execute the actual predicate on mixed Registry,
ordinary-pool and foreign-account rows. Before the fix three fail; after the
fix these tests and adjacent query/evaluator tests pass (43 tests). Component
CI, immutable publication and Server/8080 integration are still pending.
