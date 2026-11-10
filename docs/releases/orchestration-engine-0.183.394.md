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

The six SQL regressions reproduce three failures; three direct-authorization
cases separately expose two parent-alias failures. Both groups and adjacent
query/evaluator tests pass after the fix (43 tests). Component
CI, immutable publication and Server/8080 integration are still pending.

The builder already installs Canonical's SHA256-pinned rust-coreutils0.10
backport. Canonical documents sixteen matching CVEs as fixed in that exact
updates-pocket package even though the security-pocket tracker lags. Native
Trivy OpenVEX qualification now requires its installed ELF bytes, dpkg version,
image identity and exact package PURL. Raw reports and every unrelated finding
are retained. Ten negative controls reject other packages/versions/images,
forged bytes or changes to unrelated findings. No severity gate is relaxed and
the four separately tracked, unfixed rust-coreutils CVEs remain outside this VEX.
