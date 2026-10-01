# PastureStack Hazelcast Runtime

The orchestration engine embeds the reviewed runtime produced by the dedicated `distributed-cache-runtime` repository. It does not rebuild a second, diverging Hazelcast fork during every orchestration build.

## Published 5.7.4 provenance (historical)

- Source project: [`PastureStack/distributed-cache-runtime`](https://github.com/PastureStack/distributed-cache-runtime)
- Annotated release tag: `v5.7.4`
- Source commit: `21fe15f85f0eff12d3ba1f68af3e1753e90ca3bd`
- Runtime artifact: `hazelcast-5.7.4.jar`
- Artifact SHA-256: `6b768e6cff9e5281e77ad14e609b69bac6856ecd4469af827f566be95553644c`
- Embedded Jackson 3 / Jackson 2: `3.2.2` / `2.22.2`

The source project owns the Java 25 build, focused legitimate and malicious regression suite, SBOM, source and artifact scanning, and release artifact. This repository downloads that exact release asset over HTTPS, verifies the pinned bytes, safe JAR paths, license and notice, Maven identities, Jackson versions, and embedded source revision before installing it into the build-local Maven repository.

This removes the former duplicate source-download, patch, and rebuild path. Dependency changes are reviewed and tested once in the source project; orchestration consumes only the corresponding pinned release bytes.

## Published 5.7.5 provenance for Engine328

- Official numeric release: [`v5.7.5`](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.5), release ID `400661425`, public and non-draft
- Verified signed tag object: `818592fbf7dd0506862216211edc99b3e009a414`
- Tag target / artifact source commit: `a9aea563870201462dc24f778a06279a08ed5841`
- Runtime artifact: `hazelcast-5.7.5.jar`
- GitHub asset ID: `602470126`
- Actual downloaded size: `23,852,246` bytes
- Actual downloaded SHA-256: `0f536a9c7bcd00f2369586fb6ca1606f7e45f3225e24795d10d38397051c8715`
- Embedded Jackson 3 / Jackson 2: `3.2.3` / `2.22.3`

Published-asset readback matched the source, tag, and JAR identities above.
The source-identical producer merge is
`a8416bd1b96bb08250095441e9816c8116d31aec`, with tree
`1ebcb081cc575a6e785af2c37e9af3b61df03022`.
[Producer build/security run `36815272664`](https://github.com/PastureStack/distributed-cache-runtime/actions/runs/36815272664)
passed 40 suites / 510 tests with zero failures, errors, or skips.
[Producer CodeQL run `36815270196`](https://github.com/PastureStack/distributed-cache-runtime/actions/runs/36815270196)
passed with the exact Java SARIF showing 120 rules, zero results,
zero severity-at-least-7 findings, and zero unresolved rule metadata.

The Engine installer and source oracle now pin these exact official identities;
the old 5.7.4 hash is not reused. Engine328 is independently published from
source `ad43f4b6790c359e248710a39bca2f776d70be62`; official build/security run
`36818926539` passed 256 suites / 1,104 tests and the exact WAR completed
isolated H2 startup. See the [Engine release note](../docs/releases/orchestration-engine-0.183.328.md)
for actual artifact, scan and runtime boundaries. Cache producer results
do not stand in for Engine consumer checks or Server/browser acceptance.

## License

Hazelcast remains third-party software licensed by its upstream authors under Apache License 2.0. The original license and notice materials are preserved in the release artifact and in the Server runtime license bundle. PastureStack claims authorship only for its compatibility and security changes.
