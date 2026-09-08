# Orchestration Engine v0.183.293

- Accept Docker's six documented bind-propagation modes during volume preflight.
- Keep propagation modes restricted to bind mounts and reject conflicting propagation declarations.
- Restore NFS storage-driver upgrades that use the existing `shared` bind propagation contract.
