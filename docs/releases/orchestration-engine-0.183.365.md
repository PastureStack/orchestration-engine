# Engine 0.183.365 candidate

This candidate is not published. Required original component CI/CodeQL,
artifact qualification and matching Server/native acceptance remain pending.
The current published component remains Engine 0.183.364.

## Corrected native workflows

A CUSTOM Key removing its own standalone container could finish the Instance
and OS Volume removal before the queued storage-allocation removal executed.
The delayed child then lost its live Volume ancestry and was denied. The
correction binds only the exact native container DELETE, device-0 private OS
Volume and allocation/pool graph while the verified removal frames are active.
Both direct Volume removal and nested Volume deallocation are covered. A later
retry may read removed or purged ancestors only when all previously pinned
identities still match; it cannot create or replace its proof.

Current role/schema access, live Key/revision/expiry, the root grant and explicit
dependency DENY remain required. Named/shared/mounted volumes, managed-Service
instances, foreign or rebound resources and unbound jobs gain no new authority.
Unpinned live ancillary volumes retain their existing lifecycle path. Full and
legacy authorization, and generic resource readers, are unchanged.

An Alias preview with a reference hidden by the native reference manager should
return native 422 / InvalidReference. The ResourceChange error wrapper instead
read the empty top-level exception fields and lost the embedded status/code,
producing an invalid response. It now extracts only those two fields before
rebuilding the safe error. Native messages, detail, payload and the full error
object remain private. Existing 403 and 500 responses keep their status.
Archive operations share this corrected wrapper. The Catalog validator already
handles its native errors and is unchanged.

## Evidence and acceptance boundary

Thirteen focused standalone OS-allocation cleanup methods passed against the
isolated correction, including delayed direct/nested cleanup after ancestor
purge and rejection of forged, rebound or unauthorized work. The prior native
failure and original-source causal failures remain retained.

Two new focused methods passed for the Alias/native-validation correction.
The complete preview reproduced the original wrapper failure before the fix.
The checks cover same-project success, foreign-project rejection, native 422 /
InvalidReference, exact denial audit metadata, no plan insertion or native
mutation, and safe handling of direct/embedded errors. Five error variants are
parameters within one method, not five additional unique tests.

The independently checked cleanup and validation changes passed the repository
Checkstyle rules for four and three complete Java files respectively. These are
source-focused results, not the final integrated producer's CI or runtime PASS.
The executed-case gate retains all 76 existing required methods and additionally
requires the 13 cleanup methods and two new validation methods in fresh reports.
No final CI test total or artifact digest is claimed before qualification.

All dependency coordinates and security decisions remain unchanged. The four
AI opt-ins remain independently default-off. The failed runtime reports remain
failed; fresh matching Server Alias/reference and Member cleanup acceptance is
required. This candidate does not establish complete frontend or 243-group
acceptance.

## Upgrade and recovery

Consume the qualified component only through its newly published matching
Server release. Preserve data, operator settings and a compatible prior
image/database backup. A downgrade does not undo writes or resolve an already
denied child. Do not replay an uncertain operation or reuse its revoked Key.
Recovery of existing owned test resources is a separate authorized operation.
