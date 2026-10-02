# PastureStack Orchestration Engine

Orchestration Engine coordinates metadata, API, scheduling, storage,
networking, and resource lifecycle for the preserved control platform.
PastureStack is an independent community effort to preserve, audit, and
modernize the Rancher 1.6 ecosystem. It is not affiliated with or endorsed
by Rancher Labs or SUSE.

**Upstream:** [`rancher/cattle`](https://github.com/rancher/cattle). This fork
preserves upstream history, authorship, dates, tags, licenses, and bundled
dependency notices. PastureStack maintenance is consolidated after the
preserved upstream boundary.

## Current release

Candidate `v0.183.332` restores the server-owned Volume `isNative` classification
as a read-only field in both v1 frozen role schemas and v2 role overlays. It does
not change Volume CRUD permissions, infer missing fields in the browser, or
allow clients to change native classification. Eighteen targeted local tests
passed, including six new regressions. Formal artifact publication and native
Volume UI lifecycle acceptance are pending; see the
[332 candidate note](docs/releases/orchestration-engine-0.183.332.md).

The published release `v0.183.331` corrects the stopped-container mapping condition
in 330. The common selection/update predicate accepts only running/active
or stopped/inactive pairs; all source-account, unique-mapping, managed-container
and full-row compare-and-swap guards remain intact. See the
[331 release note](docs/releases/orchestration-engine-0.183.331.md) and
[immutable numeric release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.331).
The signed annotated tag binds exact CI source
`515a5d37a1194f827bc3ffde34db729905ecb2b1`; PR70 was normally merged as
`3249e77c149fb5220022baea01655dbe197c19e4` with an identical tree.
The WAR is `87,700,088` bytes, SHA256
`0c8310d9e9a872589972658d2fd8cb88f59f473ab8072a4746df5b0f4ef9e70e`.
Exact-source build 36972340448 passed 268 suites / 1,144 tests, zero failures,
errors or skips (22 native-name and six frozen-role response cases).
Six exact CI assets and three anonymous content readbacks passed; the exact
WAR also completed isolated JDK25.0.3 / H2 startup, with no network, exposed
ports or platform-data mounts. Artifact/build-image Critical and High findings
are zero; CodeQL 36972340453 retains four lower-severity findings. These scoped
checks do not claim zero risk or complete resource/role acceptance.

Published Server `v1.6.500`, source
`abdee460eb67c8cd02a2db8e9a55b15f58020d83`, consumes this exact 331 WAR in
image digest `sha256:7ffd67a7f82da0d374d7846b97b5a2fb71647ad01a159120418544591899f5f5`.
QA8080 first start/restart (`HTTP 200` / `pong`), preserved runtime/data counts
and five exact Docker/model/DOM ID-name bindings passed. Native initial/reload
technical checks passed, but Root's actual PNG review remains **VISUAL HOLD**:
four retained rollback names are clipped to indistinguishable prefixes.

Published Server `v1.6.501`, source
`ea97b199801c277efc178430b4aa6a0d4f67d25b`, also consumes the exact 331 WAR,
with Web Console `1.6.165` and image digest
`sha256:0a671e2695eecc74d79ef666267a40e81172205f0f8b1d0b12a7dbbed446becd`.
Publisher 36980705366 and official immutable readback passed. QA8080 first
start/restart each returned `HTTP 200` / `pong` on attempt 10, with zero runtime
and data-count differences; Docker health remains `null`, not a healthy claim.
The separate COUNT-derived proof binds six exact full Docker IDs/names,
including current `1i25669` and retained Server500 rollback `1i25668`.
Separate fresh native initial/reload checks and Root's two actual PNG reviews
passed for Host1 at zh-tw desktop width 1440: all six names and five rollback
suffixes are readable without overlapping details/actions. One native menu
open/close cycle and one actual server WebSocket message on reload were observed,
with zero resource writes. This is not long-term WebSocket, mobile, all-locale
or full resource/role acceptance. Server500's historical visual HOLD stays
unchanged; the full resource/role matrix remains **INCOMPLETE**.

## Historical 0.183.330 release

The previous release `v0.183.330` is a narrowly scoped imported-container
name refresh. A ping's UUID/name hint is never written as the authoritative
name: the Engine inspects the exact full Docker ID through its existing Agent
contract, and performs a name-only compare-and-swap on an eligible existing
native container. Managed service/stack names and lifecycle processing are
unchanged. See the [330 release note](docs/releases/orchestration-engine-0.183.330.md).
The official numeric [release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.330)
uses a signed annotated tag pointing to exact CI source
`3f7320a8063a5471618be5b8be6a168f49559847`.
[PR #68](https://github.com/PastureStack/orchestration-engine/pull/68) was normally
squash-merged as `b76ddcc5c24a7683ffe05fba26707b71b3b313eb`; its tree is identical
to the CI source, and the immutable release tag remains on the CI source.
The exact WAR is `87,700,053` bytes with SHA-256
`c01cbbfd63625fc09f39c5494775919aad6db22c92050b217b28b940b57e1de3`.
[Build/security run `36966573025`](https://github.com/PastureStack/orchestration-engine/actions/runs/36966573025)
passed 268 suites / 1,141 tests with zero failures, errors or skips, including
19 native-name cases and six retained GenericObject cases. Artifact and
build-image Critical/High findings are zero; the
[CodeQL run `36966573050`](https://github.com/PastureStack/orchestration-engine/actions/runs/36966573050)
retains four lower-severity findings, not an all-findings-zero claim.
Six exact CI assets are published. Anonymous content readback checked the
checksum, source revision and unit-test results; the public WAR was not
downloaded again. The exact CI WAR completed isolated JDK `25.0.3` / H2 startup
and exited 0, with no network, exposed ports or platform-data mounts. Consuming
Server image, real MariaDB concurrency and real-host/browser name-refresh
acceptance remain separate requirements; this does not establish the complete
resource/role matrix.

## Historical 0.183.329 release

The previous published release is `v0.183.329`. It closes a low-role GenericObject
read bypass for plugin capabilities in both v2-beta and the frozen v1 schemas.
Readonly/restricted clients retain resource metadata; plugin configuration is
read through its typed API. Owner/member/service storage remains unchanged.
See the [329 release note](docs/releases/orchestration-engine-0.183.329.md).

The official numeric [release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.329)
uses a signed annotated tag pointing to CI source
`14e3f0919a33282ff3026c364182f715f5dda634`.
[PR #66](https://github.com/PastureStack/orchestration-engine/pull/66) was normally
squash-merged as `b4613763a731398ee091f388ef5c9cd5b88aec9d`; its tree is identical
to the CI source, without moving the release tag to the squash commit.
The exact CI WAR is `87,690,873` bytes with SHA-256
`9f8e5736898b6d9c51cb96d5f7cdbe8e3831a29ce296909108f5f2d5a68f7ec4`.
[Build/security run `36951856238`](https://github.com/PastureStack/orchestration-engine/actions/runs/36951856238)
passed 266 suites / 1,123 tests, with zero failures, errors or skips, including
all six new GenericObject overlay/response and frozen-v1 cases. Actual artifact
and build-image Critical/High findings are zero. The
[CodeQL run `36951859325`](https://github.com/PastureStack/orchestration-engine/actions/runs/36951859325)
retains four lower-severity findings, with zero Critical/High findings; this is
not an all-findings-zero claim. The exact CI WAR completed standalone JDK
`25.0.3` startup against isolated H2 and exited 0, with no network, exposed
ports or platform-data mounts. Server consumption, full MariaDB integration,
real v1/v2-beta role/browser checks and the whole resource/role matrix remain
separate, pending acceptance gates.

## Historical 0.183.328 release

The previous published release `v0.183.328` updated platform Jackson to
`2.22.3` and the isolated WebAuthn/logging runtime to `3.2.3`, and consumes the
official Cache `5.7.5` artifact. The two Jackson namespaces remain separate.
See the [release note](docs/releases/orchestration-engine-0.183.328.md).
The official numeric [release](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.328)
uses source `ad43f4b6790c359e248710a39bca2f776d70be62`; its actual CI WAR is
`87,690,742` bytes with SHA-256
`184fb3d4a2b026560e1e60d7b444f693f79bff6c8220cc9354c1284012f6a683`.
[Build/security run `36818926539`](https://github.com/PastureStack/orchestration-engine/actions/runs/36818926539)
passed 256 suites / 1,104 tests, with zero failures, errors or skips, including
all six BodyParser and eight WebAuthnConfiguration cases. The exact CI WAR
completed standalone JDK `25.0.3` startup against isolated H2 and exited 0.
Actual artifact and build-image Critical/High findings are zero; Medium/Low
build-image findings remain recorded. The
[custom CodeQL run](https://github.com/PastureStack/orchestration-engine/actions/runs/36818926652)
retains four lower-severity findings, not an all-findings-zero claim.
Full MariaDB integration, Server consumption, browser authentication and the
resource/role matrix remain separate acceptance gates.

## Preserved 0.183.327 behavior and provenance

Certificate name/description updates preserve omitted certificate content;
deleting or removing a certificate referenced by a v2 load balancer's alternate
list is rejected just like a default certificate. Authorization and private-key
masking are unchanged. See the
[release note](docs/releases/orchestration-engine-0.183.327.md) for behavior,
tests, and compatibility details. Previous release notes remain in
[`docs/releases`](docs/releases), and the
[GitHub release history](https://github.com/PastureStack/orchestration-engine/releases)
records published artifacts.

The official numeric [release `v0.183.327`](https://github.com/PastureStack/orchestration-engine/releases/tag/v0.183.327)
is published from source commit `dce2f2473ffea1510fe10676a771eb1fe5d0b161`.
Its `cattle.jar` WAR has SHA-256
`c6d4c3003a19db19d1be73e69aa52358a0a4166bf726cbefe7e2ab9ed5664b56`;
the remote published asset's hash was independently read back and matched.
[Official build/security run `36701559252`](https://github.com/PastureStack/orchestration-engine/actions/runs/36701559252)
and [CodeQL run `36701559287`](https://github.com/PastureStack/orchestration-engine/actions/runs/36701559287)
passed, along with all 17 focused Certificate tests. The exact published WAR
completed standalone startup against isolated H2 with JDK `25.0.3` and exited
with code 0. Full MariaDB/MySQL integration and Server/browser Certificate QA
were separate and pending at that component release; the full resource/role matrix is not accepted
by these component checks.

The `v0.183.324` tag is source-only and has no published release artifact.

## Current build baseline

The build retains Java 25, Ubuntu 26.04, Maven, Liquibase, MariaDB/MySQL,
WebSocket, concurrency, runtime maintenance, and the existing direct tool
versions. Its signed Ubuntu security snapshot
pins OpenSSL CLI, library and legacy provider to `3.5.5-1ubuntu3.6`, the
official fix for [CVE-2026-84782](https://ubuntu.com/security/CVE-2026-84782).
It retains OpenSSL 3.5 and does not relax the build-image security gate.
It consumes the exact
`5.7.5` JAR from
[`distributed-cache-runtime`](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.5)
and verifies its pinned digest and dependency metadata before installing it
into the build-local Maven repository. See
[`third-party/HAZELCAST.md`](third-party/HAZELCAST.md) for provenance.

## Jackson and embedded Cache compatibility

The historical `v0.183.328` release introduced platform Jackson `2.22.3` and the
isolated WebAuthn/logging Jackson line `3.2.3`; `v0.183.329` retains these versions,
with patchless annotations remaining
`2.22`. These are the official patched versions for CVE-2026-91776 and
CVE-2026-91777; see the [release note](docs/releases/orchestration-engine-0.183.328.md).
The release pins the corresponding officially published
[`distributed-cache-runtime` `5.7.5` artifact](https://github.com/PastureStack/distributed-cache-runtime/releases/tag/v5.7.5),
because `5.7.4` embeds both older Jackson versions. The actual release JAR,
source commit, checksum, and asset ID were read back and verified; see the
[Cache provenance](third-party/HAZELCAST.md). Engine's producer and consumer
checks are independently recorded; Cache's results are not substitutes for
Engine or Server acceptance. No Server496 image or deployment is claimed here.
The published 327 provenance above remains historical and unchanged.

## Build and validation

Before publishing an Engine artifact or a Server image, run the complete
JDK 25 package gate:

```sh
bash scripts/check-cattle-jdk25-full-package
```

After the gate passes, package and check the release artifact:

```sh
ENGINE_VERSION=0.183.332 bash scripts/build --release
bash scripts/check-release-artifact dist/artifacts/cattle.jar
```

The gate builds every Maven module, checks dependency hygiene and packaged
classes, and starts the standalone application against isolated H2. Full
database and platform checks require isolated MariaDB/MySQL and companion
services. See [COMPATIBILITY.md](COMPATIBILITY.md), [SECURITY.md](SECURITY.md),
and [ORIGIN.md](ORIGIN.md) for those boundaries and source provenance.

For a deliberate frozen-schema source migration, first run
`GenericObjectAuthOverlayTest` with the supported Maven/JDK toolchain. Use its
Surefire `java.class.path` property to run
From the repository root, run
`java --class-path <test-classpath> scripts/java/UpdateFrozenGenericObjectSchemas.java`.
The one-time producer accepts no path arguments, requires the reviewed 328
snapshot SHA-256 values before deserialization, and rejects unexpected classes
with a bounded serialization filter. `--check-filter` verifies the current two
role snapshots and rejects a graph with a deserialization callback without
executing it; it does not write files. Already migrated snapshots are rejected
by the migration path.
The helper applies only the two declared field denials to the existing v1 role
snapshots, checks the persisted schema contract for unrelated drift, and then
writes `readonly.ser` and `restricted.ser`. Run
`FrozenGenericObjectRoleSchemaTest` afterwards. It is an offline source-generation
step, never a deployed-runtime patch.

## Language and licensing

The web console supplies user-facing translations. API fields, persisted
values, event names, identifiers, and remote error payloads remain
compatibility data and are not translated.

The inherited project remains under the [Apache License 2.0](LICENSE).
Inherited work and bundled dependencies retain their own attribution and
licenses.
