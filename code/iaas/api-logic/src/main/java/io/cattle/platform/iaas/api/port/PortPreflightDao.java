package io.cattle.platform.iaas.api.port;

import io.cattle.platform.core.model.Host;

import java.util.List;

public interface PortPreflightDao {
    List<Host> getEligibleHosts(long accountId);
    List<PortOwner> getPortOwners(long accountId);
}
