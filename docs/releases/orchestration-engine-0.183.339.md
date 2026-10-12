# Engine 0.183.339 — Registration Basic routing

This independent component release is based on Engine 0.183.338. It is not the
broader integration candidate and does not change an already-published Server image.

- Release tag: `v0.183.339`
- Source commit: `436a37215bc5702e516ecf1feb6753b4be5bf602`
- `cattle.jar` SHA-256: `bce020a298ebb89ef3d14ba0666f040e4a732288d1b100da9859dcf957a7981d`

Reserved Basic usernames can be claimed by their dedicated credential protocol.
Registration retains its native token validation and restricted schema. A
rejected registration credential never falls back to an API Key or browser
session; unclaimed Basic credentials still use only the normal Key validator.
No database schema migration or new registration credential is introduced.

Ten focused source tests passed on the minimal source before the numeric version
bump: six authentication-precedence cases, one reserved-username case and three
registration-token cryptographic cases. The version-only rebuild does not rerun
them; build evidence records the relevant compiled-class byte comparison.

Required protected-branch CodeQL checks passed before the normal merge; the
release asset digest matches the packaged artifact and its SHA-256 checksum.
Native Server onboarding and active-agent acceptance are separate assembly gates.
Component packaging is not whole-platform acceptance.
