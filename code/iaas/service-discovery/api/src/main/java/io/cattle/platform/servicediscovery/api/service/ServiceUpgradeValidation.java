package io.cattle.platform.servicediscovery.api.service;

import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.model.Service;

/** Read-only native strategy invariants and port/volume preflight for an authorized upgrade target. */
public interface ServiceUpgradeValidation {
    void validate(Service service, InServiceUpgradeStrategy strategy);
}
