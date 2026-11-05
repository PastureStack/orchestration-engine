# Engine 0.183.370 candidate

This source candidate is not published or runtime-qualified. Matching Web
Console 1.6.209 / Bridge 0.2.11 and a new Server assembly remain separate.

Host metadata plans now accept only name and description through the existing
human-reviewed typed change owner. Native PUT capabilities, current role and
Key policy, revision/CAS checks and the durable single claim remain mandatory.
Host lifecycle, labels, scheduling and agent configuration are not added.

Host detail returns at most fifty freshly readable container references from
a native query bounded to fifty-one mappings. Container and Stack visibility
are checked independently. Host and Volume parent resolution follows the native
serviceIds relation only when the persisted parent is truly absent; a bounded
unique ordinary Service and a freshly readable Stack must still match at the
final native check. Hidden, multiple or changed relations stay unknown without
disclosing identities or counts. Empty references never establish unused,
empty or safe-to-delete status. Existing read audit and deletion rules remain.

Catalog launch now wires the existing lifecycle binding into the real native
ObjectManager. Lazy, memoized composition avoids eager dependency cycles and
binds the Stack ID before its CREATE process can be scheduled. Ordinary creates,
an empty handler set and legacy explicit-list wiring keep their contracts.
The fix does not retry uncertain mutations or retrospectively bind orphan plans.

Twenty-four new exact required methods join the preserved 142 (166 total).
Focused source checks passed; full original Security/JDK25 and CodeQL gates,
artifact qualification and native business acceptance remain pending. C03
re-extraction preserves all 1396 HTTP tuples and operation/grant semantics.
No zero-vulnerability or whole-243-group completion claim is made.

Builder downloads retain Maven 3.9.16 and its exact SHA512 pin, using Apache's
official CDN with the original archive as a transport-only fallback. Checksum
failure remains terminal; this does not change runtime dependencies.
The fixed Guava URL and SHA256 also remain unchanged; bounded curl rejects
HTTP errors before the JAR checksum and installation checks.
The build environments also replace APT and libapt-pkg7.0 with the official
USN-8996-1 version 3.2.1ubuntu0.1 before package operations, addressing
CVE-2026-109518 without changing the release vulnerability threshold.
A narrow native OpenVEX fixed statement covers only these two exact Ubuntu
amd64 package PURLs while the scanner vendor data lags the official fix. CI
verifies all nineteen installed package-owned ELF hashes and the native package
pair on the same image, preserves the original raw report, and rejects any
unrelated finding removal. The existing pip overlay and release thresholds remain.
