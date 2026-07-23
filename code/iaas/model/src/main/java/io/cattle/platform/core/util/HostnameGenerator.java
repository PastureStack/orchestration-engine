package io.cattle.platform.core.util;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.NetworkConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Network;
import io.cattle.platform.object.util.DataAccessor;

public class HostnameGenerator {

    private static final ConfigProperty<String> DEFAULT_DOMAIN = ArchaiusUtil.getStringProperty("default.network.domain");
    private static final ConfigProperty<String> DEFAULT_PREFIX = ArchaiusUtil.getStringProperty("default.hostname.prefix");

    private static final ConfigProperty<String> DEFAULT_LOCAL_DOMAIN = ArchaiusUtil.getStringProperty("default.local.network.domain");
    private static final ConfigProperty<String> DEFAULT_LOCAL_PREFIX = ArchaiusUtil.getStringProperty("default.local.hostname.prefix");

    private static final ConfigProperty<String> DEFAULT_SERVICES_DOMAIN = ArchaiusUtil.getStringProperty("default.services.domain");

    public static String getDefaultDomain(boolean local) {
        return local ? DEFAULT_LOCAL_DOMAIN.get() : DEFAULT_DOMAIN.get();
    }

    public static String getDefaultPrefix(boolean local) {
        return local ? DEFAULT_LOCAL_PREFIX.get() : DEFAULT_PREFIX.get();
    }

    public static String getDefaultServicesDomain() {
        return DEFAULT_SERVICES_DOMAIN.get();
    }

    public static String getServicesDomain(Network network) {
        String servicesDomain = DataAccessor.fieldString(network, NetworkConstants.FIELD_SERVICES_DOMAIN);
        return servicesDomain == null ? getDefaultServicesDomain() : servicesDomain;
    }

    public static String lookup(boolean local, Instance instance, String address, Network network) {
        return lookup(local, instance.getHostname(), instance.getDomain(), address, network.getDomain());
    }

    public static String lookupHostname(boolean local, String instanceHostname, String address) {
        String hostname = local ? instanceHostname : null;

        String prefix = getDefaultPrefix(local);

        if ( hostname == null ) {
            if ( address == null ) {
                return null;
            }

            return String.format("%s%s", prefix, address.replaceAll("[.:]", "-"));
        } else {
            return hostname;
        }
    }

    public static String lookup(boolean local, String instanceHostname, String instanceDomain, String address, String networkDomain) {
        String hostname = lookupHostname(local, instanceHostname, address);
        if ( hostname == null ) {
            return null;
        }

        String domain = local ? instanceDomain : null;
        if ( domain == null && local ) {
            domain = networkDomain;
        }

        if ( domain == null ) {
            domain = getDefaultDomain(local);
        }

        return String.format("%s.%s", hostname, domain);
    }

}