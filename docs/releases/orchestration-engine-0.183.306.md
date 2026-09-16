# Orchestration Engine 0.183.306

- Put the reviewed external identity list, including `oidc_user` and
  `oidc_group`, in the IAAS API packaged defaults that production Archaius
  startup loads.
- Keep the installer-facing `cattle-global.properties` list aligned while
  preserving environment and database overrides.
- Retain schema startup ordering, stable option merging, de-duplication, and
  rejection of unknown external identity types.
- Change the Spring regression test to obtain the identity list through the
  production `ConfigConfig.GlobalProperties()` loader instead of injecting a
  test-only value.
- Add a source gate for the runtime-loaded defaults so a future release cannot
  pass by updating only the installer resource.

`0.183.305` contained the schema ordering fix but did not place the reviewed
list in a Java runtime-loaded defaults file. Server consumers must use
`0.183.306` or newer and verify both `/v1/schemas/projectMember` and
`/v2-beta/schemas/projectMember` in the integrated candidate runtime before
publishing.

Use this release with Web Console `1.6.119` and Authentication Service
`v0.4.38`.
