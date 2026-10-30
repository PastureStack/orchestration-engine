# Orchestration Engine 0.183.361

Source candidate; publication and designated runtime acceptance are pending.

An authorized standalone Container DELETE can enumerate its own NIC again after
the native owner has marked that NIC removed. The previous live-only dependency
resolver rejected the native cleanup, and the parent treated the structured
authorization failure as an unknown retryable exception. The container could
remain `removing` indefinitely.

Keep the exact verified server-side `instance.remove` execution frame and its
owned retired NIC/IP relationships for that synchronous cleanup. Recheck current
Key revision, role, ownership, UUIDs, mappings and explicit denies. A stop frame,
unrelated NIC or shared/foreign IP cannot use this path. A genuine structured
child authorization denial terminates the parent; transient failures retain
their previous behavior, and failed completion audit retries only the audit.

External Service and DNS Alias preview previously failed with 503 because the
typed audit target allowlist omitted their registered resource types. Add those
exact types and the new Certificate metadata target to the original durable,
metadata-only audit owner. Audit failure remains fail-closed; this is not an
authorization grant.

The fixed private audit owner now receives the broker's configured upstream.
Previously its separate 8082 literal and cleared Java child environment diverged
from the image's 8083 upstream, causing real audit queries to fail. Retain the
existing default; the new fixed child accepts HTTP(S) loopback origins only,
reconstructed from fixed scheme/host literals and a validated numeric port.
Invalid or nonlocal child topology fails safely; the ordinary broker retains
its existing operator configuration interface.
Credentials remain private stdin, and callers cannot choose a URL or executable.
Matching Server broker code is required; ordinary native authorization remains.

Certificate name/description changes use the existing human-approved resource
plan and a normal native metadata-only PUT. PEM, private key and certificate
chain are excluded from the plan snapshot and output and are not rewritten.
Material replacement and other certificate operations remain in the human UI.

Focused causal tests retain the original failures and cover network ownership,
parent process identity, current Key and explicit DENY boundaries, terminal audit
replay, certificate CAS/private-data behavior and the actual typed audit sink.
Required producer/CodeQL checks and complete designated workflows must pass
before this candidate is published or recorded as runtime accepted.

Do not repair an old operation by changing its captured Key identity or patching
database state. After a qualified deployment, verify old denied work is terminal
before a separately authorized operator operation. Retain the previous compatible
Server image and data backups; downgrade does not reverse completed mutations.
