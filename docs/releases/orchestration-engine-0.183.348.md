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

## Published artifact and Server pairing

The immutable published source is `f19cd64183c0fa93464e9a9f9c2477c9e902b56d`.
The published `cattle.jar` has SHA-256
`72581f6eead8633b6bbafbe15512cd44561b9ff25df68d00dd7120ad507aa449`.
Official component publication and immutable readback are complete. Published
Server `v1.6.531` packages this artifact with Web Console `1.6.186`;
documentation commits do not replace the published producer or artifact.

## Native acceptance

Upgrade from Server 530 repaired the existing unbound Default. One restart
introduced no duplicate stacks, services or drivers. A fresh Server 531 with
three persistent volumes registered one host through the normal public workflow.
Default bound to template `1pt1`; four core stacks and six core services became
active and healthy. Metadata, managed DNS, HTTPS egress HTTP 200 and published
host-port access passed.

Server restart, Docker restart, guest reboot with a changed boot ID, and probe
workload recreation through the native API preserved readiness. Native Chrome
154 acceptance confirmed eleven distinct driver IDs/names on both API roots,
zh-tw/en-us at 1440px/390px, and the same identities after restart. The published
Server deployment and subsequent restart also passed.

Narrow affected-path acceptance completed with the existing host defaults and
unchanged role logic; broader role/backend matrices are outside this verification.
Use a complete Server image for upgrade or rollback; do not replace `cattle.jar`
in a running production server.
