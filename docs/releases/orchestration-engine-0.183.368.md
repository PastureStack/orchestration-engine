# Orchestration Engine v0.183.368

Adds the bounded External Service CREATE TCP healthCheck: required port plus optional interval, responseTimeout, healthyThreshold and unhealthyThreshold. The original JSON types and closed fields survive immutable preview, normal human approval, native dispatch and verified stopped-create readback. Omission retains None; timing defaults are not added. Existing role, Key, Stack scope and native permissions remain in force.

Original CI: 425 suites, 3947 tests, 0 failures/errors, 1 inherited skip; 132 required methods passed. Seven new TCP methods and 41 inherited methods in the affected classes passed. The single original artifact is 13359474102 from Security 41901558534; both independent CodeQL runs passed. Producer `1098514b65518d2cde4068884cf6f130576290f3` and normal PR156 merge `1cbce0e73821c5b59f221c36673026be328014c3` have the same tested tree `5f23ee46c56132bd90e4bbdd6a58ce13fc8f9615`. No rebuild, test rerun or scanner rerun was used for publication.

The original raw/effective scanner reports and exact existing VEX remain included. Effective Dapper has 44 MEDIUM and 7 LOW findings without fixes; these are not claimed patched. The workflow did not produce SLSA attestations. GitHub's immutable release attestation and anonymous asset-byte verification are separate publication checks.

Matching Server runtime, native-role/Key checks and human-review acceptance for this new tuple remain pending. Full frontend coverage remains in progress.
