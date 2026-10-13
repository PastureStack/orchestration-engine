# Engine 0.183.347 — Subscription terminal lifecycle

This source change completes the subscription observer path shared by API Key
WebSocket reads. It does not replace HTTP decision/response events or turn an
accepted handshake into a successful completion.

- Observe physical close, server close, I/O failure and live authorization
  failure using the writer's actual first cause.
- Remove registrations that finish after the close fence, including a timed-out
  registration wait. Closed streams cannot resume delivering resource bodies.
- Retry failed terminal delivery through the existing bounded retry scheduler;
  physical cleanup remains exactly once. Retained retry state contains only the
  lifecycle metadata, not a request, token, writer or authorization grant.
- Keep the first terminal result across outbox replay and account-locked DAO
  persistence; the existing delegated-event alias remains compatible.
- Never log client close text, arbitrary identifiers or Throwable contents.

Full/legacy optional authorization, live restricted-Key checks, owner permissions,
deny precedence, frozen APIs and durable audit bounds are unchanged. There is no
database migration or public privilege addition.

Focused tests cover close/registration races, callback reentrancy, first-cause
consistency, bounded delivery retry, safe logging, live authorization, outbox
replay and DAO idempotence. Package gates, immutable component publication and
assembled-Server native acceptance remain separate evidence. Exhausted storage
failure or abrupt process termination must not be reported as durable success.

Use a complete Server image for upgrade or rollback. Do not replace `cattle.jar`
inside a running production container.
