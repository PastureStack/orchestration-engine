# Engine 0.183.377 candidate — not published

Native audit queries can select one canonical API Key before pagination while retaining the current actor, account and live Key authorization. Only MYSQL/MARIADB advertise this exact selector. Unsupported H2/HSQLDB deployments retain the existing bounded-history behavior and explicitly reject selector requests.

The private audit source adapter can query the fixed capability after fresh native GET/schema/Key checks, returning no audit rows. Its only new optional input metadata is keyId and capabilitiesOnly; no new resource method, arbitrary field or SQL endpoint is exposed. Errors never trigger an unfiltered retry, and no Key creation-time assumption truncates imported or HA history.

All 229 prior required methods remain registered with nine new selector/capability methods (238 total). Focused source results are separate from normal module CI and original artifact qualification, which remain pending. Web216/Bridge0.2.18 remain immutable; a Server assembly must qualify this backend tuple against their existing contract. Publication, native audit/layout acceptance and whole-243 completion are not claimed.
