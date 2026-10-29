# Orchestration Engine 0.183.357

This source corrects two native metadata adapters and authorization of typed
resource-operation reads. Qualification and deployment acceptance are separate;
this is not complete frontend WebMCP coverage.

## Correct behavior

The normal validator decodes a wire resource ID before an internal domain
request is authorized. The parent resolver previously decoded that decimal
value again, turning a valid target into an unknown scope. A human-approved
Stack create could complete while its original scoped Key received 403 when
reading the operation. The resolver now retains the already-decoded internal
identity. Public scope IDs still require typed roundtrips; foreign projects,
wrong types, explicit child DENY, stale revisions and live owner limits remain.
No account field, read grant or permission bypass is added.

ProcessPoolManager now supplies a read-only canonical bean key, separate from
the original display name, metrics and null native resource ID. The AI adapter
uses an opaque hash of that owner identity, so unnamed or same-name pools no
longer break metadata lists or become ambiguous after reordering. It does not
expose the private bean key or invent a database ID. The unsupported
activeSetting collection is excluded from the AI read allowlist; its ordinary
schema and permissions are unchanged.

## Validation and compatibility

Focused regressions use the real formatter, validator, Key authorization and
typed manager, plus real Spring map injection and frozen-schema serialization.
The reviewed operation projection retains all 1,386 HTTP/semantic tuples.
Original source producers and historical failure evidence remain separate.
Required full component CI/CodeQL, immutable artifact verification and remaining
8080 workflow/read checks must pass before claiming runtime completion.
Previously passed and unaffected role, layout and metadata checks are retained.

Web Console 1.6.199 and bridge 0.2.1 remain compatible. No dependency update,
database migration, authentication change, firewall change or runtime patch is
required. Roll back through the assembled Server procedure with retained data
and a matching backup; keep plan receipts and do not replay uncertain writes.
