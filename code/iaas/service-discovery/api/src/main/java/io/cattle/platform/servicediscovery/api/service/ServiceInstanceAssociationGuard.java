package io.cattle.platform.servicediscovery.api.service;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import java.util.function.Supplier;

/** Domain boundary for new managed or selector-owned instance associations. */
public interface ServiceInstanceAssociationGuard {
    ServiceExposeMap associate(Service service, Instance instance, Supplier<ServiceExposeMap> next);
    boolean blocksNewAssociations(Service service);
}
