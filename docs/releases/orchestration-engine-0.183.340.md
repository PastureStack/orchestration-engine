# Engine 0.183.340 — Native environment creation

This independent component release is based on Engine 0.183.339. It is not the
broader integration candidate and does not change an already-published Server image.

- Release tag: `v0.183.340`
- Source commit: `ff0a41e7676211043eb4f755a306a9e2a19abee1`
- Source tree: `2d5bc914742d76d2b0b9f10a2098e3b8bd4ecd7d`
- `cattle.jar` SHA-256: `5e5a1b908b43bf3824fc3bb2108bd661cd5199ce9bcfd187e0b18b5608a38cea`

The formal Server 1.6.522 publication gate stopped on ordinary-user environment
creation through a native legacy API Key. Its predecessor 0.183.339 correctly
restored reserved registration-token Basic routing, but the shared lifecycle
authorization still required owner membership before the creation workflow could
establish it. The account-inherited `user` kind was also set to `project` too late.

The focused repair keeps the normal object/process lifecycle. It sets the actual
project kind before insertion and binds initial work to the resource created by
the project manager, not an ID supplied in JSON. Framework-captured scheduling
identity and live principal, Key revision/expiry and project POST capability are
checked again at execution. Initial owner membership and the four builtin Docker
networks are the only creation dependencies; ordinary operations on existing or
foreign resources retain their original authorization.

This release does not include unrelated storage, selector, action-registry,
WebSocket or API replay feature changes. The released registration authentication
and MFA/session behavior are preserved. No database migration or configuration
weakening is introduced.

Thirty-four focused project-creation tests passed: five lifecycle cases and
twenty-nine authorization cases, with no failures, errors or skipped cases.
Their production and test class bytes remain unchanged through the formal
`scripts/build --release` package, which passed the original artifact gate.

The ten Engine339 registration/authentication cases are carried forward by
unchanged source and compiled-class byte comparisons; they were not rerun and
are not counted as newly executed Engine340 tests. The release publishes the
original `cattle.jar` and portable `cattle.jar.sha256` from clean merged source,
not a broader integration artifact or a newly compiled replacement.

Native Server onboarding, active-agent lifecycle and complete platform acceptance
remain separate assembly gates. This component release does not claim those
results. Installation continues to use the separately published
[Server image](https://github.com/PastureStack/server#current-release).
