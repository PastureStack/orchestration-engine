package io.cattle.platform.resource.pool.port;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.resource.pool.PooledResourceItemGenerator;
import io.cattle.platform.resource.pool.PooledResourceItemGeneratorFactory;
import io.cattle.platform.resource.pool.impl.StringRangeGenerator;
import io.cattle.platform.resource.pool.util.ResourcePoolConstants;

public class HostPortGeneratorFactory implements PooledResourceItemGeneratorFactory {

    private static final ConfigProperty<String> HOST_PORT_START = ArchaiusUtil.getStringProperty("host.port.start");
    private static final ConfigProperty<String> HOST_PORT_END = ArchaiusUtil.getStringProperty("host.port.end");

    @Override
    public PooledResourceItemGenerator getGenerator(Object pool, String qualifier) {
        if ((pool instanceof Host) && ResourcePoolConstants.HOST_PORT.equals(qualifier)) {
            return new StringRangeGenerator(HOST_PORT_START.get(), HOST_PORT_END.get());
        }

        return null;
    }

}
