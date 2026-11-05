# Engine 0.183.373 candidate — not published

This candidate adds read-only ProcessInstance discovery, details and bounded failure diagnostics through the existing find/get tools. Native global process schemas, current actor authority and the native Key owner remain decisive; Project ownership does not confer global process permission. Unsupported custom process Key scopes remain denied.

Only registered process/handler names, fixed phase/exitReason values and safe timing metadata are projected. Raw exception messages, causes, stack traces, process payloads, credentials and replay are excluded. Empty, unavailable or truncated diagnostics remain unknown and never establish a successful resource operation.

Queries retain the native 51/50 bound. Diagnostic work shares a 256-entry/32KiB safe UTF-8 budget across all logs, roots and handlers, with fixed truncation metadata and no recursive child/cause traversal. Normal human UI remains available for raw error inspection.

The Dapper scan retains four exact CVE-2026-82560 Perl-family MEDIUM findings without an official Ubuntu26.04 package fix. They remain disclosed in the exact unfixed ledger; fixed, unregistered and HIGH/CRITICAL findings still block. Product JAR and build-image evidence remain separate.

Normal required CI, original artifact qualification, matching-component integration, platform native acceptance, weak-model acceptance and affected layout checks remain separate. This is not a whole-group or whole-243 acceptance claim. No component is published by this source change.
