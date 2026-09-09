# PastureStack Hazelcast Runtime

The orchestration engine embeds the reviewed runtime produced by the dedicated `distributed-cache-runtime` repository. It does not rebuild a second, diverging Hazelcast fork during every orchestration build.

## Provenance

- Source project: [`PastureStack/distributed-cache-runtime`](https://github.com/PastureStack/distributed-cache-runtime)
- Annotated release tag: `v5.7.4`
- Source commit: `21fe15f85f0eff12d3ba1f68af3e1753e90ca3bd`
- Runtime artifact: `hazelcast-5.7.4.jar`
- Artifact SHA-256: `6b768e6cff9e5281e77ad14e609b69bac6856ecd4469af827f566be95553644c`
- Embedded Jackson 3 / Jackson 2: `3.2.2` / `2.22.2`

The source project owns the Java 25 build, focused legitimate and malicious regression suite, SBOM, source and artifact scanning, and release artifact. This repository downloads that exact release asset over HTTPS, verifies the pinned bytes, safe JAR paths, license and notice, Maven identities, Jackson versions, and embedded source revision before installing it into the build-local Maven repository.

This removes the former duplicate source-download, patch, and rebuild path. Dependency changes are reviewed and tested once in the source project; orchestration consumes only the corresponding pinned release bytes.

## License

Hazelcast remains third-party software licensed by its upstream authors under Apache License 2.0. The original license and notice materials are preserved in the release artifact and in the Server runtime license bundle. PastureStack claims authorship only for its compatibility and security changes.
