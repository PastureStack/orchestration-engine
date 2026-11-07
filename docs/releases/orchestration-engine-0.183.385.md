# Orchestration Engine 0.183.385 candidate

Ordinary Stack metadata plans accept the native `group` field. The value is a
string of at most 255 characters or null; case, whitespace, comma-separated tags
and the difference between null and empty string are retained. System/catalog
Stacks remain outside this typed subset.

This candidate also corrects logical Volume creation completion. The native
`volume.create` process ends in `inactive`, with inactive allocation and no Host,
mount or storage-pool map. Version 0.183.384 incorrectly required `detached`, so
an already-created record could remain reported as running. Preview and verified
readback now require the actual inactive state; all identity, driver, allocation
and no-mount checks remain. The native manager and state machine are unchanged.

Execution uses the existing native PUT manager and current resource/field
capabilities, account permissions and bound Key policy. Preview and execution
bind the Stack UUID, project UUID and configuration digest; human confirmation
and verified readback remain required. Raw configuration is not added to public
plan or metadata output. No HTTP route, role grant or permission bypass is added.

Matching Web Console 1.6.225 / Bridge 0.2.27 and a qualified Server assembly are
required. This is source-candidate documentation: normal CI, immutable publication
and native acceptance are pending. Existing native APIs and unrelated workflows
remain on their current paths; complete WebMCP coverage is not claimed.
