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

The source patch is based on Engine 0.183.340. At the source-validation stage,
publication identity, checksum and the corresponding Server release had not
yet been verified. Their post-publication records follow below.

## Post-publication component acceptance

The original Windows validation record above is retained. Linux subsequently
executed the original POSIX permission method once with matching input class
hashes: one started and passed, with zero failures, assumptions or skips.
The prior 18 Windows cases were not rerun. Together these are 19 actual passing
cases from separate executions, not a newly rerun 19-case suite.

The immutable [Engine `v0.183.341` release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.341)
uses normally merged source `b245cf778479445ded0786d3898b03fea153212e`, tree
`65cecdc6bc894fb210f4c2ebb8fd7b624936e27c`. Its complete original-packager
`cattle.jar` is 87868337 bytes with SHA-256
`3ede1ad28b7a7e0754c4c953bd486162f7ffe0b2f76ce384ec485632af6b8e6d`.
Both original release assets were verified by anonymous actual-byte readback.
The canonical Engine publication readback receipt has SHA-256
`849ffb4924e58ab32da582fe2bf66c94307e6aa54aeef5125946eb3f2ef92328`.

The tested HA production class bytes match the packaged artifact; existing
Engine340 registration and API authorization source/classes are unchanged.
Published Server `v1.6.523` pairs this Engine with Web Console `1.6.183`.
Final Server native HA/role and deployment acceptance remain separate and
belong in the Server release record. Component publication and these 19 source
cases do not establish complete BE-06 or whole API Key matrix acceptance.
