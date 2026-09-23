# Orchestration Engine 0.183.320

Project-member collection access checks the requested project against the token policy before loading members.

Before this patch, a token authorized for project A could send
`X-API-Project-Id: A` with `GET /v1/projectMembers?projectId=B` or
`GET /v2-beta/projectMembers?projectId=B`. The authenticator selected A, but
the custom collection resource manager loaded B's members without checking
access to B. A token with no access to B must now receive 404 before any B
membership is loaded. Direct member-ID access retains its existing project
check. A malformed project ID also returns 404 without a database member read.

The change is intentionally limited to the custom `projectMember` resource
manager. It does not change project roles, OIDC identity mapping, generic
resource authorization, or membership writes. Focused tests cover the foreign
collection, authorized collection, malformed ID, and direct-ID allow/deny
paths. The Server consuming this release must also run v1/v2 API and browser
role matrices against a disposable project, including an authorized header
with a different unauthorized query project, before deployment.

Rollback: restore the prior immutable Server image containing Engine
`0.183.319` only if the authorization regression is understood; that version
does not contain this collection-read protection. No database migration is
required for this patch.
