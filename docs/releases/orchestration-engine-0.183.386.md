# Orchestration Engine 0.183.386 candidate

Ordinary Service dependency plans accept mutually exclusive `links` entries with
an opaque target reference and native alias name, or the existing `serviceRefs`
list. Each branch replaces the complete set (maximum 16); an empty list clears
it. Native name bounds, case and null/empty plain-link compatibility remain.
Only the full-set owner uses exact target/name mutation, so removing one alias
does not remove another alias to the same target. Legacy singular/LB/bulk native
callers retain their original null/wildcard behavior. The Engine receives only
references resolved and authorized by the existing typed transport.

ExternalService creation accepts the native HTTP `requestLine` using the same
1,024-character and CR/LF/NUL bounds as ordinary Service health configuration.
TCP null behavior, timing and threshold bounds are retained. Human review and
exact approved/native health configuration equality remain required.

The deployed385 LB scale failure exposed a product frame mismatch: both metadata
and scale use native `PUT`/`service.update`, while the observer required metadata-
only attestation. Scale now has its own exact approved scale/old.scale frame,
current target/Stack UUID and owner checks, persisted process equality and a
single-process CAS. Original metadata attestation stays strict. Current Key,
revision, revocation and role checks remain under the original native owner;
native manual readiness still rejects global services. The native PUT can save
desired fields before a process fails, so the original failed plan is not retried,
rewritten or counted as success.

Source assembly: 415 retained required cases plus 10 new methods (425); 74 planned
class roots (13 current, 61 carry); pure C03 extraction 490 inputs with exactly the
same 1,396 HTTP tuples, no role, route or operation grant delta. Source SER/schema
inputs are unchanged. Current original CI, compiled class/role-schema evidence,
SBOM/security qualification, publication, deployment and fresh native/weak-model
acceptance remain PENDING. No old FAIL is promoted and no full coverage is claimed.
Matching Web Console support and a qualified Server assembly are required.
