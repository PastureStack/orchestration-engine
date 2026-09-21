# Orchestration Engine 0.183.314

- Keep the frozen v1 `projectMember.externalIdType` options aligned with the
  reviewed core schema so `oidc_user` and `oidc_group` survive v1 environment
  and membership creation after an upgrade.
- Preserve every historical provider option already present in the v1 schema
  and de-duplicate the resulting list in stable order.
- Limit compatibility enrichment to `projectMember.externalIdType`; unrelated
  frozen schemas and enum fields remain unchanged.
- Preserve the runtime identity boundary from 0.183.312 and account activation
  ordering from 0.183.313. Unknown external identity types remain rejected.

The regression test proves both the reviewed option union and the
unrelated-schema boundary. Runtime acceptance creates fresh OpenID Connect
users, provisions v1 project members, compares v1 and v2 schema options, and
then verifies environment visibility and role-specific operations.
