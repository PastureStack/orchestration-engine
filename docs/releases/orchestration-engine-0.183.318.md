# Orchestration Engine 0.183.318

- Restore `restricted` OpenID Connect login for an existing active account
  whose project membership is stored against its stable `rancher_id`, including
  membership in the shared Default environment.
- Resolve the external identity to the exact account before introducing any
  stable account identity into the authorization decision.
- Keep `required` mode explicit allow-list only. Stable project membership is
  never supplied to that check and cannot become an allow-list bypass.
- Keep inactive and unknown accounts fail-closed and preserve all existing
  direct and group roles, including `noaccess`.

The focused auth-logic tests cover identity-link account resolution, the
restricted stable-membership path, and the required-mode bypass boundary.
The runtime matrix must prove owner, member, restricted, read-only, and
no-access behavior through both v1 and v2 APIs, exact stack visibility, denied
direct UI routes, and six independent OpenID Connect browser sessions before a
Server release consumes this Engine.
