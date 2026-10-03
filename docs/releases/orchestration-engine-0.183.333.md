# Orchestration Engine 0.183.333 — candidate

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

## Pending acceptance

This is not yet a published component or Server image. Exact CI artifact
readback, isolated startup, immutable publication, Server packaging and QA8080
native API Key lifecycle acceptance remain required. Old failed QA receipts stay
HOLD; they are not rewritten as PASS. The full resource/role matrix is incomplete.

## Upgrade and rollback

No migration is required. Preserve Compose environment, named volumes, restart
policy, AppArmor, origin and authentication settings. Consume only a new immutable
Server image after the component gates pass; do not overwrite prior tags. A
rollback restores the old POST-action response behavior and must not be described
as preserving this fix. No production deployment is authorized by this candidate.
