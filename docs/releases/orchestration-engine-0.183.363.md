# Engine 0.183.363

Source candidate; component publication and designated QA remain pending.

External Service target previews now read the actual current hostname and IP
addresses from their native dynamic fields. These fields have no ordinary Java
getters, so earlier previews could incorrectly show the before value as null.
The existing display sanitization, native owner, authorization and complete-data
compare-and-set snapshot remain unchanged.

Seven real ServiceRecord cases cover hostname-to-IP, IP-to-hostname, IP-to-IP,
hostname changes, null/empty values, safe projection and no record mutation. Four
nonempty before-value cases reproduced the failure before the two-line fix; all
seven pass afterwards. The Engine362 ServiceIndex cleanup fix is retained.

This is a shared preview correctness repair, not a new public API grant, a CAS
redesign or complete frontend automation. Normal Full/legacy behavior and the
four independent default-off AI features remain unchanged.

The candidate also updates the official MariaDB Connector/J patch from 3.5.10
to 3.5.11, retaining the maintained 3.5 line and existing database configuration.
The same floor is required for both Maven dependencies and packaged JARs.
This addresses CVE-2026-119210, CVE-2026-119276, CVE-2026-119281 and
CVE-2026-119280, identified by the current release scan; no vulnerability
exception or suppression is added. See the
[official 3.5.11 release](https://github.com/mariadb-corporation/mariadb-connector-j/releases/tag/3.5.11).
The updated producer must pass the original release gate before publication;
database compatibility and the assembled Server workflows remain separate QA.
