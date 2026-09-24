# Orchestration Engine 0.183.321

Direct `projectMember` ID reads now require the row to be active and not
removed, matching the existing collection query. The project-access check
remains in place. A removed member is therefore not exposed to an authorized
reader who retained its old `1pm` ID; an active member remains readable.

On Server v1.6.464, a disposable project's removed row was absent from the
member collection but `GET /v1/projectMembers/1pm...` and the corresponding
`/v2-beta` request returned 200. Focused manager tests first failed for
inactive and removed rows, then passed after the guard was added. Release
acceptance must repeat the remove-and-direct-read lifecycle on both API
versions with a disposable project and confirm 404, as well as active-row 200,
foreign-project 404, and restoration of QA fixtures.

This patch changes no OIDC mapping, project role, membership write, schema, or
database table. No migration is required. Rollback to Engine 0.183.320 is
technically possible but restores the stale-member read defect; restrict
access and assess retained member IDs before doing so.
