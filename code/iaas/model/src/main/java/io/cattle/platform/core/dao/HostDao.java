package io.cattle.platform.core.dao;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.IpAddress;
import io.cattle.platform.core.model.PhysicalHost;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;

import java.util.List;
import java.util.Map;

public interface HostDao {

    public final static ConfigProperty<Long> HOST_REMOVE_DELAY = ArchaiusUtil.getLongProperty("host.remove.delay.seconds");
    public static final ConfigProperty<Long> HOST_REMOVE_START_DELAY = ArchaiusUtil.getLongProperty("host.remove.delay.startup.seconds");

    List<? extends Host> getHosts(Long accountId, List<String> uuids);

    boolean hasActiveHosts(Long accountId);

    Host getHostForIpAddress(long ipAddressId);

    IpAddress getIpAddressForHost(Long hostId);

    Map<Long, List<Object>> getInstancesPerHost(List<Long> hosts, IdFormatter idFormatter);

    PhysicalHost createMachineForHost(Host host, String driver);

    Map<Long, PhysicalHost> getPhysicalHostsForHosts(List<Long> hosts);

    void updateNullUpdatedHosts();

    List<? extends Host> findHostsRemove();

}
