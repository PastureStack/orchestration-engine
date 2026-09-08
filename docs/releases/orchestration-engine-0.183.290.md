# Orchestration Engine v0.183.290

Keep named-volume preflight aligned with the actual container creation path.
Docker-local named volumes can legitimately have one API inventory record per
host; those records remain host-local until placement and are not an ambiguous
environment-wide shared-volume lookup.

Preflight now checks the same shared-or-unmapped candidate set used by
`InstanceVolumeLookupPreCreate`. Multiple resolvable shared volumes still fail
closed, as do a storage-driver mismatch or an unusable resolved volume. No
volume is deleted, renamed or implicitly migrated by this change.

The regression suite covers the per-host local-volume case and retains the
existing rejection cases. The source gate binds the runtime lookup used by the
preflight implementation.
