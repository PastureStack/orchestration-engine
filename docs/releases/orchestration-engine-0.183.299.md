# Orchestration Engine 0.183.299 candidate (unreleased)

- Preserve the established Docker host ranges and add only Engine `29.8.0` as
  an exact match. Set `newest.docker.version` to `v29.8.0`; later versions remain
  untested, not implicitly supported.
- Keep the pinned build-container Docker CLI at `29.7.2`. Its version is not
  the host daemon compatibility policy.
- Check the 11 relevant boundary and host-status cases, and verify both
  settings inside the packaged `cattle-app-config` JAR. The Server must embed
  this new `0.183.299` JAR, not reuse `0.183.298` with documentation changes.

Release remains pending actual Ubuntu 26.04 / Docker 29.8.0 acceptance with
each installed firewall backend mode (`iptables-legacy`, `iptables-nft`, or
native nftables), plus preserved older-host compatibility. Backend detection
must use host state, not Ubuntu version; no test may silently switch a host's
default firewall backend. After the runtime gate passes, the release workflow
must build, test, scan, publish the versioned JAR, and record its SHA-256 for
the Server pin and release evidence.
