# Orchestration Engine v0.183.289

Preserve typed runtime and GPU DeviceRequests through container, primary service,
sidekick and upgrade APIs and Docker/Compose conversion. Export GPU reservations
without silently flattening capability alternatives.

Validate GPU count versus device IDs, shared-memory versus IPC, CPU/PID limits,
device paths, temporary filesystems, sysctls and ulimits before scheduling.
The existing project/user schema permission boundaries remain in force.
Hardware settings never implicitly enable privileged mode or host IPC.

Use the Node Agent v0.13.24 and Compose Executor v0.14.35 release packages for
the corresponding new hardware contracts. Release those packages before
deploying this engine. Existing installation-level overrides must be checked
before rollout; do not treat the new default as proof every host is upgraded.

The frontend can suggest only capabilities reported by the selected host.
Inventory is not an exclusive GPU scheduler and does not establish CUDA,
ROCm or media-driver compatibility. Such workloads require real hardware tests.
