# Orchestration Engine 0.183.354

This release refreshes the existing Java 25 build/runtime baseline to
Temurin 25.0.5+8 and installs Ubuntu's reviewed CA package
20260816ubuntu0.26.04.1. The additional security lock applies available official
Ubuntu 26.04 micro-updates to existing affected packages, without adding,
removing or downgrading packages or replacing the base release.
Official artifacts remain SHA256-pinned.

The Ubuntu Python interpreter ABI is retained while its installed 3.14 package
set receives Ubuntu security revision 3.14.4-1ubuntu0.2 (CVE-2026-4360 and
CVE-2026-15308). Build and test tooling uses
the downstream pip 26.2.2+pasturestack.1 rebuild and certifi 2026.10.19,
including the pip-vendored certificate bundle and new `venv`/`virtualenv`
seeds. This rebuild uses official urllib3 2.8.0 through pip's upstream
vendoring tool and original patches, fixing CVE-2026-97689 in its reachable
download path. It is not an official PyPA pip release. All inputs and the
reproducible wheel are SHA256-pinned, with licenses and vendored SBOM retained.
The image build checks each seed path, streaming bounds and TLS trust failures.

No API authorization, schema, OIDC/MFA, scheduler, networking backend or
persistence semantics are changed. Existing endpoint-reconciliation and
session protections remain in place. Publication and Server integration
remain subject to the required CI and runtime checks.
