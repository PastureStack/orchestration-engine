# Orchestration Engine 0.183.354

This release refreshes the existing Java 25 build/runtime baseline to
Temurin 25.0.5+8 and installs Ubuntu's reviewed CA package
20260816ubuntu0.26.04.1 without changing the other snapshot package pins.
Official artifacts remain SHA256-pinned.

The Ubuntu Python interpreter ABI is retained while its installed 3.14 package
set receives Ubuntu security revision 3.14.4-1ubuntu0.2 (CVE-2026-4360 and
CVE-2026-15308). Build and test tooling uses
pip 26.2.2 and certifi 2026.10.19, including the pip-vendored certificate
bundle and new `venv`/`virtualenv` seeds. The image build checks each path.

No API authorization, schema, OIDC/MFA, scheduler, networking backend or
persistence semantics are changed. Existing endpoint-reconciliation and
session protections remain in place. Publication and Server integration
remain subject to the required CI and runtime checks.
