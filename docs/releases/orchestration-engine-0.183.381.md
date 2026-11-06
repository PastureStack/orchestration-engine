# Orchestration Engine 0.183.381 candidate

Typed TCP load-balancer creation previously rejected native numeric Stack and backend references after normal framework validation. The approved public IDs were compared through a parser that requires canonical wire IDs, causing a false PermissionDenied. The internal comparison now accepts only exact positive native numbers. String references continue through the original canonical parser, and the shared backend comparison also preserves strict completion readback.

The regression uses the real ApiKeyTargetResolver and DefaultIdFormatter with native framework conversion. The original published380 classes reproduce two failures; the repair passes 49 focused tests and changed-source Checkstyle. Zero, negative, fractional, non-finite, overflowing, numeric-string and wrong-prefix references remain rejected.

C03 retains all 1396 operation tuples and 472 source inputs. Its reviewed source fingerprint changes only for this implementation; anchors, role/schema grants, native routes, actions, Key policy and human confirmation remain unchanged. The existing 450-suite CI threshold and all 269 required methods remain required.

Formal CI, original packaged-artifact qualification, immutable publication, matching Server assembly and native TCP acceptance remain pending. This candidate does not claim completion of the 243 frontend groups.
