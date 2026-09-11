# Orchestration Engine 0.183.298

- Fix `PUT /v2-beta/mfaSettings/global` returning 405 because the live admin
  authorization overlay advertised create instead of update.
- Preserve all 37 declared settings fields, including advanced policy,
  security confirmation, and read-only local-administrator recovery status.
- Restore missing account-holder MFA confirmation inputs and results;
  replace the administrator operation-field wildcard with explicit rights.
- Retain administrator-only policy updates, single-use expiring confirmation,
  and non-echoed SMTP/password confirmation inputs. No authentication bypass
  or broader collection-create/delete permission is introduced.
- Add actual overlay-order regression tests and packaged-overlay checks;
  retain the frozen v1 hardware and MFA contracts.
