package org.apache.commons.configuration;

public class SystemConfiguration extends MapConfiguration {

    public SystemConfiguration() {
        super(System.getProperties());
    }

}
