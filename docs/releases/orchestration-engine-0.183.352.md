# Orchestration Engine 0.183.352

## Project permission checks without response-side grants

Custom API Key target resolution checks the owner's live project membership
and requires owner authority for an inactive project. Those checks previously
also populated request-local project capabilities and object-access grants.
When account collections were filtered, that side effect could add project
actions to the response even though the normal or Full-Key path did not add
them there.

The resolver now uses a pure permission check. It does not alter request-local
capabilities or grants. The normal Project resource manager still uses the
materializing authorization path, preserving its existing owner and set-members
capabilities and direct project GET, mutation and action loading behavior.
An earlier object grant cannot substitute for the live membership check.

This is a correction to authorization-check side effects and response metadata;
it is not evidence that an unauthorized write succeeded. Full and legacy Keys
gain no new scope, registry or read-grant requirement. Background authorization
does not create an HTTP context or request-local grants. Existing live owner,
scope, explicit-deny, revision, expiry and write-completion checks remain in
place.

## Compatibility and validation boundaries

There is no database migration, API schema change or policy-record rewrite.
The write-completion, audit sanitization and logging protections from
[351](orchestration-engine-0.183.351.md) remain in place. Docker CLI 29.7.2
continues to use the digest-pinned Go 1.27.3 builder; the Security policy's
compiler-version description is aligned with the existing recipes, without
changing policy or release thresholds.

Focused source regressions cover the pure-check boundary and retained normal
project authorization behavior. Source validation, required CI checks, immutable
Engine publication, assembled Server qualification and native permission-matrix
acceptance are distinct evidence. This document alone establishes none of those
results; use the official release assets and their checksums for the published
artifact identity.

Retain the previous Server image and persistent volumes for code rollback. Do
not restore stale API Key policies or patch deployed binaries to apply this
correction.
