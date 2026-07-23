# Patched Hazelcast Build

The orchestration engine uses Hazelcast as an embedded third-party dependency. Release builds reproduce a patched artifact locally instead of publishing or trusting an opaque replacement binary.

## Provenance

- Upstream project: [`hazelcast/hazelcast`](https://github.com/hazelcast/hazelcast)
- Upstream version: `5.7.0`
- Pinned upstream commit: `60c31e3750cbad64f5720e2e02f0a9830973193c`
- Source archive SHA-256: `e44d7ebeb6400309a7b672f2190180925f81cdbedab44f2247cd266d03f4fa88`
- Local artifact version: `5.7.0-pasturestack.2`

The build downloads the archive for the exact commit, verifies its SHA-256 checksum and safe paths, applies [`hazelcast-5.7.0-jackson-security.patch`](hazelcast-5.7.0-jackson-security.patch), builds the Hazelcast core module, verifies the embedded dependency coordinates, normalizes JAR metadata to the release source timestamp, and installs the result only into the build-local Maven repository.

## Patch scope

The patch changes only Hazelcast's pinned Jackson versions:

- Jackson 3: `3.1.2` to `3.1.5`
- Jackson 2: `2.21.2` to `2.21.5`

It does not remove upstream attribution, authorship, notices, or licensing information.

## License

Hazelcast remains third-party software licensed by its upstream authors under Apache License 2.0. The original license and notice materials are preserved in the built artifact and in the Server runtime license bundle. PastureStack claims authorship only for the tracked compatibility and security patch.
