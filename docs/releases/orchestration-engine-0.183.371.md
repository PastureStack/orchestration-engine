# Orchestration Engine v0.183.371

Fixes typed Host metadata preview, apply and receipt for native Docker Hosts. Host model identity is checked independently of its driver kind; role, Key scope, account and removal checks remain authoritative.

Catalog launch now supports the actual client’s exact version-root and schema GET handshake. Authenticated delegation still rechecks the event proof, current original owner, source and frozen Stack before metadata. Null root types are handled safely. Repeated metadata reads do not grant resource writes or retry uncertain mutations.

The existing pinned final OpenSSL/Python/Curl/CA/Brotli/PCRE packages are installed before the frozen base plan through normal APT. Three old bootstrap pins align with the existing final versions; final versions, source URLs, SHA pins, security lock and snapshot dates remain unchanged. Official APT fixed-package VEX and the existing pip-vendor qualification remain exact and package scoped.

Original CI: 434 suites, 3988 tests, 0 failures/errors, 1 inherited skip; all 173 required methods passed, preserving 166 and adding seven exact Host/Catalog regressions. The related 41 class methods comprise seven new and 34 inherited methods. C03 retains 458 source inputs and 1396 unchanged policy tuples. Security 42018874056, producer `55baa21d7952f6d0d54076af6b250a4d60f2d5d7` and normal PR163 merge `c6f9ebe1fd3a0843e36737722e017cb401cdd628` share tested tree `4aa43f55a5a5ebc865003a2b534dc2b91cb7e2ee`. The original CI artifact is published without rebuilding or rerunning tests/scans.

Organization CodeQL reports zero results. Independent extended CodeQL retains the same four lower findings as published370, with zero Critical/High, unresolved rules or changed-source findings. Original raw/effective scan evidence is retained; effective Dapper 44 MEDIUM and 7 LOW findings are not claimed patched. No workflow SLSA attestation is provided; immutable-release cryptographic verification and anonymous asset readback are separate guarantees.

Native Host metadata and Catalog launch acceptance requires a matching Server assembly. Runtime acceptance and whole-family or 243-group completion are not claimed.
