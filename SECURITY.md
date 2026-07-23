# Security Policy

## Supported state

Only artifacts referenced by a published PastureStack release and its checksum manifest are supported. An unreviewed worktree artifact is not a release.

## Security boundaries

- Database credentials, API keys, tokens, certificates, secrets, event payloads, and audit data are sensitive.
- Migration and compatibility code can mutate persistent state; backup and rollback verification are mandatory.
- Docker, host, network, storage, and agent integrations can reach privileged infrastructure.
- Bundled and build-time dependencies require review before every release.
- The build pins the Hazelcast source archive by commit and SHA-256, applies only the tracked Jackson security patch, and verifies the embedded dependency coordinates before installing the locally built artifact.
- Do not commit credentials, private endpoints, production data, certificates, or captured traffic.

## Reporting

Report suspected vulnerabilities through this repository's private security advisory channel. Do not place live secrets or production data in a public issue.
