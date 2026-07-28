# Security Policy

## Supported state

Only artifacts referenced by a published PastureStack release and its checksum manifest are supported. An unreviewed worktree artifact is not a release.

## Security boundaries

- Database credentials, API keys, tokens, certificates, secrets, event payloads, and audit data are sensitive.
- Migration and compatibility code can mutate persistent state; backup and rollback verification are mandatory.
- Docker, host, network, storage, and agent integrations can reach privileged infrastructure.
- Bundled and build-time dependencies require review before every release.
- The build pins the Hazelcast source archive by commit and SHA-256, applies only the tracked Jackson security patch, and verifies the embedded dependency coordinates before installing the locally built artifact.
- WebAuthn4J's pinned `tools.jackson` 3.1 runtime is isolated by package
  namespace from the platform's `com.fasterxml.jackson` 2.22 runtime.
  Packaging rejects any version drift, extra Jackson generation, or
  overlapping class path.
- External login identities are keyed by the exact provider, issuer, and
  immutable subject. Usernames, display names, and email addresses are not
  automatic account-matching keys.
- Provider changes keep security enabled and use short-lived, single-use,
  account-and-identity-bound proofs. Active local system administrators retain
  an MFA-gated recovery path when an external provider is unavailable.
- MFA factors and pending challenges are stored as encrypted secrets or
  one-way hashes as appropriate. Challenges are short-lived, attempt-limited,
  account-bound, and single-use. Factor or recovery changes revoke active
  sessions.
- Factor enrollment, recovery-code generation, and recovery-address
  verification require the account holder's own authenticated session.
  Administrative access permits inspection and revocation, not impersonated
  enrollment or recovery-secret retrieval.
- WebAuthn validates the exact origin, relying-party ID, challenge, user
  presence, user verification, credential ownership, and signature counter.
  Plain HTTP is allowed only for isolated loopback tests.
- Remote SMTP requires STARTTLS or implicit TLS with certificate hostname
  verification. Email codes recover an account after primary authentication;
  they are not authentication factors.
- Do not commit credentials, private endpoints, production data, certificates, or captured traffic.

## Reporting

Report suspected vulnerabilities through this repository's private security advisory channel. Do not place live secrets or production data in a public issue.
