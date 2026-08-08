package io.cattle.platform.resource.pool.mac;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.resource.pool.PooledResourceItemGenerator;
import io.cattle.platform.resource.pool.impl.AbstractTypeAndQualifierPooledItemGeneratorFactory;
import io.cattle.platform.resource.pool.util.ResourcePoolConstants;

public class MacAddressPrefixGeneratorFactory extends AbstractTypeAndQualifierPooledItemGeneratorFactory {

    private static final ConfigProperty<String> MAC_PREFIX_START = ArchaiusUtil.getStringProperty("mac.prefix.start");
    private static final ConfigProperty<String> MAC_PREFIX_END = ArchaiusUtil.getStringProperty("mac.prefix.end");

    public MacAddressPrefixGeneratorFactory() {
        super(null, ResourcePoolConstants.MAC_PREFIX);
    }

    @Override
    protected PooledResourceItemGenerator createGenerator(Object pool, String qualifier) {
        return new MacAddressGenerator(MAC_PREFIX_START.get(), MAC_PREFIX_END.get());
    }

}
