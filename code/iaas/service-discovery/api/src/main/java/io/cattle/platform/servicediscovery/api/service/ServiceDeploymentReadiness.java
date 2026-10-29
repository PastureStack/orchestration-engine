package io.cattle.platform.servicediscovery.api.service;

import io.cattle.platform.core.model.Service;

/** Read-only deployment observation of a Service already authorized by the caller. */
public interface ServiceDeploymentReadiness {
    /** Uses fresh domain state and its own deployment strategy; grants no resource access. */
    boolean isReady(Service authorizedService);

    /** The caller supplies an authorized, freshly locking-read Service, never a cached or ordinary-read snapshot. */
    default boolean supportsManualScale(Service authorizedService) { return false; }
}
