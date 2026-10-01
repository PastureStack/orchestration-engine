# Orchestration Engine 0.183.328

## Scope and official correction

This release updates the platform `com.fasterxml.jackson`
runtime from `2.22.2` to `2.22.3` and the isolated `tools.jackson` runtime from
`3.2.2` to `3.2.3`. Patchless `jackson-annotations` stays `2.22`.
The maintainer advisories for
[CVE-2026-91776](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54)
and [CVE-2026-91777](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24)
list these same-line patched versions. Dependency presence and release-gate
failure are established; this note does not assert a tested exploit against
the deployed service.

Both runtime generations remain necessary: platform JSON uses Jackson2,
while WebAuthn4J and logstash-logback-encoder use Jackson3. The packaged gate
requires exactly one patched core and databind JAR per generation, with
disjoint class namespaces. Older patch pairs, missing generations, duplicate
payloads, and overlapping namespaces are rejected.
Multi-release class paths are normalized before namespace and overlap checks;
module descriptors are excluded because they describe distinct modules rather
than duplicate loadable classes. Regression fixtures exercise root and
multi-release foreign classes for both core/databind generations.

## Embedded copy and verified Cache provenance

The Server496 failed-run scan also identifies both older databind versions
inside `hazelcast-5.7.4.jar`; updating Engine dependencyManagement alone is not
sufficient. The dedicated cache producer has officially published `5.7.5`
with the same Jackson patch versions. Published-asset readback verified source
`a9aea563870201462dc24f778a06279a08ed5841`, GitHub asset `602470126`, size
`23,852,246` bytes, and JAR SHA-256
`0f536a9c7bcd00f2369586fb6ca1606f7e45f3225e24795d10d38397051c8715`.
The installer and source oracle pin those exact identities; see the
[Cache provenance](../../third-party/HAZELCAST.md) for the signed numeric tag
and producer build/security/CodeQL evidence. Engine consumes an official,
checksum-pinned artifact, not a second local fork.

The signed numeric `v0.183.328` tag points to tested source
`ad43f4b6790c359e248710a39bca2f776d70be62`. PR #64 was normally merged after
required checks passed, and its merged tree matches the tested source tree.
The official release WAR is the actual CI artifact, not a local rebuild:
`87,690,742` bytes, SHA-256
`184fb3d4a2b026560e1e60d7b444f693f79bff6c8220cc9354c1284012f6a683`.
Server496 assembly and QA remain separate; no Server496 image digest or
deployment is established by this component release.
Server496 run `36812661669` remains failed; its original evidence is not changed.
The published Engine327 and its release notes remain historical records.

## Executed Engine328 validation

The dependency and exact dual-namespace fixture gates passed. The official
[build/security run](https://github.com/PastureStack/orchestration-engine/actions/runs/36818926539)
passed 256 suites / 1,104 tests, zero failures, errors or skips. All six
`BodyParserRequestHandlerTest` and eight `WebAuthnConfigurationTest` cases
passed. Four new
`BodyParserRequestHandlerTest` cases cover the real gdapi mapper, object/list
merging, Unicode, unknown fields, and malformed JSON returning 400. Three new
`WebAuthnConfigurationTest` cases cover WebAuthn4J JSON/CBOR and the credential
data/base64url storage roundtrip. These configuration/serialization tests are
not a full Passkey login. Actual packaged inventory checked 191 embedded JAR
hashes and 204 CycloneDX components. Source secret findings and artifact
Critical/High findings are zero. The build image has zero Critical/High but
198 Medium / 22 Low findings, still recorded rather than concealed.
The [custom Java CodeQL run](https://github.com/PastureStack/orchestration-engine/actions/runs/36818926652)
passed with actual SARIF containing three severity-4 cookie findings and one
severity-5 lock finding; Critical/High and unresolved rule metadata are zero.
The exact CI WAR completed standalone JDK 25.0.3 startup against isolated H2,
without network or platform data volumes, and exited 0. Full MariaDB and
Server/browser acceptance remain pending. No security gate or VEX exception
was relaxed.
