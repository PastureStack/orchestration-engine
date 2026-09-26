# Orchestration Engine 0.183.323

This release updates FreeMarker from 2.3.34 to 2.3.35. The older version was
reported as CRITICAL CVE-2026-84939 in the Server v1.6.470 final rootfs scan.
The dependency is owned by Engine and was already updated on Engine main;
this release gives the corrected build a new numeric version and rejects the
older JAR at the Engine artifact gate. No runtime JAR is patched and no finding
is suppressed.

The existing template configurations continue to pin
`freemarker.template.Configuration.VERSION_2_3_0`. There is no API, schema,
database, or template configuration change. Acceptance requires the JDK 25
package gate, a release archive containing exactly one
`WEB-INF/lib/freemarker-2.3.35.jar`, and a fresh scan of the final Server image
before publication. Rolling back to `0.183.322` restores FreeMarker 2.3.34.
