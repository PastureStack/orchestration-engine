# Orchestration Engine 0.183.386

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

Readable-target denied audits retain a trusted target identity only after fresh
native visibility checks. Hidden targets remain excluded. Native permission
checks are preserved.

Original JDK 25 CI
[42407902564](https://github.com/PastureStack/orchestration-engine/actions/runs/42407902564)
passed 475 suites / 4,241 tests, zero failures/errors and one inherited skip.
All 427 required methods passed: 415 retained and 12 new. Compiled evidence covers
74 roots (14 current, 60 byte-identical carry), all 16 serialized schemas and
175 schema JSON inputs. C03 retains 490 inputs and the same 1,396 HTTP tuples,
with zero role, route or operation grant delta.

Producer `062d8e336339f1b55749fc5731a2f7797a12a005` and signed merge/tag target
`bf297828e9e02e09bf6f291b037b22fb8801afd3` share tested tree
`dccd2fca2a302c9f7289604b343df4a451c7be02`. The original JAR SHA256 is
`caab40811c651725b0d73ad861a744ce991466f75819e453e826d0b1a796e929`.
All twelve [public assets](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.386)
passed anonymous SHA256 readback and immutable cryptographic release verification.
Security, original SBOM and compiled role-schema qualifications passed.

Matching Web Console support, qualified Server deployment and fresh designated
native/weak-model acceptance remain separate. No old FAIL is promoted and no
full frontend coverage is claimed.
