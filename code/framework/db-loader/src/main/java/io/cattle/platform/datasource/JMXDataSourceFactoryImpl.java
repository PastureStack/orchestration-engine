package io.cattle.platform.datasource;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

import org.apache.commons.dbcp2.BasicDataSource;

public class JMXDataSourceFactoryImpl extends DefaultDataSourceFactoryImpl {

    private static final ConfigProperty<String> PREFIX = ArchaiusUtil.getStringProperty("dbcp.jmx.prefix");

    @Override
    protected BasicDataSource newBasicDataSource(String name) {
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setJmxName(PREFIX.get() + name);
        return dataSource;
    }

}
