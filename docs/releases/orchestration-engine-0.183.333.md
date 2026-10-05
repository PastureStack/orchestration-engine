# Orchestration Engine 0.183.333 — published

## Root cause and scope

`WrappedResource` treated every POST response as a creation response. An API Key
deactivate action returns the reloaded credential, so its stored `secretValue`
could appear despite the schema's read-on-create-only contract. This observation
does not establish that the stored value was a plaintext secret; it still breaks
the response boundary. GET and PUT redaction did not establish POST-action safety.

The fix shares the actual resource dispatch distinction (`POST` and no action)
across the manager, JSON writer and attachment helper. Fields marked `o` are
redacted for actions as well as reads, updates and deletes. The existing null
representation is retained. A true create still delivers its first secret.
Other readable fields are not name-matched or hidden; API schemas, role overlays,
account boundaries and persistent data are unchanged. Legacy wrapper signatures
remain available and fail closed without explicit creation context.

## Focused verification

Nineteen local tests passed, zero failures/errors/skips: 14 new regressions and
five adjacent controls. Coverage includes true creation, POST actions (including
empty/unknown actions), other methods, both API roots, actual manager/writer and
attachment callers, bean and additional-field values, priority fields, legacy
constructors, ordinary readable fields, the actual frozen v1 user API Key `o`
flag, and the existing Volume formatter contract. An independent read-only
source review found no confirmed surviving bypass or introduced regression in
the five changed production files. The formal release gate requires the new
named regressions in its executed unit-case evidence.

## Publication and remaining acceptance

The immutable [numeric release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.333)
was published on 2026-10-03. Its `cattle.jar` SHA256 is
`8c42c0982cbc2f4569fa265ad320b341551758cb4fc0bc6d79ba06d70e20d328`;
the release also supplies a SHA256 checksum, source revision, unit-test evidence
and CycloneDX inventory. Published Server `v1.6.516` packages this component.
Its isolated QA startup/restart passed, but publication and startup are not
complete native UI or resource/role acceptance. Old failed QA receipts stay
HOLD; they are not rewritten as PASS. The full resource/role matrix remains
incomplete.

## Upgrade and rollback

No migration is required. Preserve Compose environment, named volumes, restart
policy, AppArmor, origin and authentication settings. Consume only a new immutable
Server image; verify its published tag and digest, and do not overwrite prior tags. A
rollback restores the old POST-action response behavior and must not be described
as preserving this fix. Publication does not authorize production deployment.
