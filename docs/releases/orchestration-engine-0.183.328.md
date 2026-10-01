# Orchestration Engine 0.183.328 candidate — not released

## Scope and official correction

This source-only candidate updates the platform `com.fasterxml.jackson`
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

Engine328 has not yet been built or published. The successful Cache producer
checks do not establish Engine compatibility or replace its pending consumer
tests. There is no Engine328 release artifact, source/build/SBOM/runtime PASS,
or Server496 image digest at this stage.
Server496 run `36812661669` remains failed; its original evidence is not changed.
The published Engine327 and its release notes remain historical records.

## Required Engine328 validation before publication

Run the dependency and exact dual-namespace fixture gates, then the existing
`JacksonJsonMapperTest` and MFA/WebAuthn-adjacent tests. Four new
`BodyParserRequestHandlerTest` cases cover the real gdapi mapper, object/list
merging, Unicode, unknown fields, and malformed JSON returning 400. Three new
`WebAuthnConfigurationTest` cases cover WebAuthn4J JSON/CBOR and the credential
data/base64url storage roundtrip. These seven Java cases have not yet been
compiled or executed; configuration-only tests are not a full Passkey login.
Review effective POM
and dependency tree for both patched lines. Before publishing, the existing
full package, actual JAR inventory/SBOM/security, and standalone runtime gates
remain required. No database or QA authentication is part of this candidate
preparation, and no security gate or VEX exception is relaxed.
