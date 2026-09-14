# Orchestration Engine 0.183.299

- Preserve the established Docker host ranges and add only Engine `29.8.0` as
  an exact match. Set `newest.docker.version` to `v29.8.0`; later versions remain
  untested, not implicitly supported.
- Keep the pinned build-container Docker CLI at `29.7.2`. Its version is not
  the host daemon compatibility policy.
- Check the 11 relevant boundary and host-status cases, and verify both
  settings inside the packaged `cattle-app-config` JAR. The Server must embed
  this new `0.183.299` JAR, not reuse `0.183.298` with documentation changes.
- Preserve the network Metadata contract by mapping `is_default` and
  `host_ports` independently. This restores host-port publication for
  non-default per-host networks without misclassifying them as the project's
  default network. Regression coverage exercises all four Boolean combinations.

The versioned JAR is built, tested, and scanned from this release commit. Its
SHA-256 is recorded with the release asset for the Server pin and release
evidence. The downstream Server release must still exercise the integrated
Engine on Ubuntu 26.04 / Docker 29.8.0 with the host's installed firewall mode
(`iptables-legacy`, `iptables-nft`, or native nftables), plus preserved
older-host compatibility. Backend detection must use host state, not Ubuntu
version; no test may silently switch a host's default firewall backend.
