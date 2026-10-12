# Engine 0.183.341 — HA database backup

This patch fixes HA database size and backup handling. It uses the configured
MySQL host, port, user, password and database instead of a hardcoded root
connection. The password is supplied only to the child process environment,
not its arguments or diagnostic output.

The command must exit successfully and produce nonempty output before the
download response begins. SQL is spooled in an owner-only temporary directory
and file. Failed, interrupted, empty or timed-out commands return the existing
`DumpFailed` error without private diagnostic details; the child and temporary
storage are cleaned up.

Existing HA permissions and API v1/v2-beta routes are unchanged. This release
does not modify OIDC, MFA, HAProxy, firewall backends, data migrations or the
unrelated pending API Key operation registry.

## Validation

The two directly affected test classes cover configured connection arguments,
password handling, successful output, failed/empty output, timeout, interrupt,
response failure, temporary storage and cleanup. On Windows, 18 cases passed
and the POSIX-only permission case was skipped. Linux permission and native
backup verification must be recorded separately; this source result alone is
not a completed runtime or whole-platform acceptance claim.

The source patch is based on Engine 0.183.340. Publication identity, checksum
and the corresponding Server release are recorded after release verification.
The existing published installation remains the reference until that pairing
is available.
