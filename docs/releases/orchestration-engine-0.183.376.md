# Engine 0.183.376 candidate — not published

Standalone Container creation now accepts a bounded list of native port specifications through the existing typed plan. The native Instance parser and current port-availability checks run before confirmation and apply, without creating a Container during preview. The exact mappings are shown to the human reviewer. Name, image, description, command and entry point retain their existing behavior.

Create leaves the Container stopped. Successful readback proves its saved mapping configuration, not a live host binding or reservation. Starting it requires a separate approved plan. Service fixed/dynamic port allocation and health checks remain unchanged; environment, volumes, arbitrary launch configuration and other advanced Container fields remain outside this typed subset.

Current native roles, live Key scope/revision, human confirmation and durable operation claims remain authoritative. All 222 prior required methods remain registered, with seven new Container port methods (229 total). Local focused source controls passed; required CI, original artifact qualification, publication and designated native acceptance are pending. No whole-family or whole-243-group completion is claimed.
