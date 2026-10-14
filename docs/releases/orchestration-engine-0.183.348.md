# Engine 0.183.348 — Default project template bootstrap

The shared Default environment now uses the packaged `PastureStack Native`
default, matching the public Catalog's actual `project.yml` name. The former
`Cattle` default did not match that document, leaving a clean installation
without a template binding or the four required system stacks.

Template creation and catalog reload share the existing template lock. They
bind only the current-version, unremoved `adminProject` project when its template
ID is empty and a public template matches the effective configured name.
Persisting the binding before the existing system-stack trigger also repairs
an unbound Default whose Catalog template was installed before this correction.
Existing bindings, explicit custom default settings and other projects are
preserved. No alias fallback, direct database repair, manual stack creation,
provider/backend change or schema migration is introduced.

With zero hosts, system-stack and service definitions may exist without running
containers. The existing first-active-host trigger starts their services;
network, Metadata, DNS and health readiness require host runtime acceptance.
API Key authorization and subscription terminal/audit contracts are unchanged.

The 10 new bootstrap cases and 4 existing Catalog/model cases passed with no
failures, errors or skips. They cover fresh creation, existing-template repair,
repeat/concurrent reload, preserved bindings and settings, non-Default projects,
and the public/removal/version boundaries. The fixture is the exact native
`project.yml` from the Server 530 pinned Catalog commit
`b6b658888fce50d3ec217eb4eba0f26ab0113baf`, verified with SHA-256
`4430246526cc6b858b939110e008480c213f0f41f660df25a02f4cf4cdd260c5`.
These focused tests preceded the mechanical Maven version update; the tested
runtime source and class hashes are retained in the focused receipt.

Source checks, complete artifact packaging, immutable publication and assembled
Server acceptance remain separate gates. Use a complete Server image for
upgrade or rollback; do not replace `cattle.jar` in a running production server.
