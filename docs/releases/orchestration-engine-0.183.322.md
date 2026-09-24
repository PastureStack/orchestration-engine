# Orchestration Engine 0.183.322

A retried account purge resumes networks left in `removing` after their
`removed` timestamp was written. The previous purge selected only networks
with a null removal timestamp, so an interrupted `network.remove` could leave
an orphan that a later purge would skip. The new retry checks only that same
account's still-removing networks. Completed `removed` or `purged` networks
are not processed again.

The distributable Engine archive must not contain `cattle-dev-*.jar` or
`dev-defaults.properties`. CI now invokes the release build profile, and the
artifact gate checks outer entries and every bundled JAR. The v0.183.321
published archive contains both forbidden development files; its SHA-256 is
`1be55ad6395989e4b73de102ef0db730ac6d5c378daef7a2f521ad74c85121ed`.
That archive fails the new gate with `ENGINE_RELEASE_DEV_JAR_FORBIDDEN`.

Focused acceptance includes the account-purge retry tests, six positive and
negative release-content fixtures, a complete `scripts/build --release`
archive check, and confirmation that the new archive has no forbidden entry.
There is no API, schema, or database migration. Rollback to 0.183.321 would
restore the interrupted-network purge defect and the development defaults in
the published archive; assess remaining network rows before rolling back.
