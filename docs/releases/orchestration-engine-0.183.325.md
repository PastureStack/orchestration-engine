# Orchestration Engine 0.183.325

This release carries the ProjectTemplate API correction from the 0.183.324 source tag.
Non-admin v1 readers receive `projectTemplate.isPublic` as a read-only field;
the admin-only create and update permission is unchanged. Non-admin callers no
longer see a misleading `remove` action link on a template they do not own,
including public templates with `accountId: null`. Private owners and admins
retain the link. Direct mutation authorization remains subject to the existing
account policy.

The `0.183.324` tag was not published as a GitHub Release because its source
hygiene gate detected a deprecated URL constructor in a new test. This release
uses the supported URI conversion in that test. There is no database migration
or new API field. Rolling back to `0.183.323` restores the previous response
shape and action-link behavior.

Acceptance includes the focused schema and output-filter tests, the complete
Engine release gate, and verification of the resulting Server assembly before
rollout.
