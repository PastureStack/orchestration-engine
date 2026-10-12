# Engine 0.183.340 candidate — Native environment creation

Source candidate only; no 0.183.340 artifact has been published.

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

This candidate does not include unrelated storage, selector, action-registry,
WebSocket or API replay feature changes. The released registration authentication
and MFA/session behavior are preserved. Focused tests, immutable artifact/source
identity, publication and final Server native/active-agent results must be recorded
after they actually complete. No database migration or configuration weakening is
intended. Installation continues to use the separately published Server image.
