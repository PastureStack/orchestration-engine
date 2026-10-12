# Engine 0.183.339 — Registration Basic routing (candidate)

This is an unpublished minimal candidate based on released Engine 0.183.338.
It is not the broader integration candidate or a published Server assembly.

Reserved Basic usernames can be claimed by their dedicated credential protocol.
Registration retains its native token validation and restricted schema. A
rejected registration credential never falls back to an API Key or browser
session; unclaimed Basic credentials still use only the normal Key validator.
No database schema migration or new registration credential is introduced.

Ten focused source tests passed on the minimal source before the numeric version
bump: six authentication-precedence cases, one reserved-username case and three
registration-token cryptographic cases. The version-only rebuild does not rerun
them; build evidence records the relevant compiled-class byte comparison.

Native host registration, active-agent acceptance and protected-branch CI remain
separate pending gates. Component packaging is not whole-platform acceptance.
