package io.cattle.platform.archaius.sources;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigurationStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.configuration.AbstractConfiguration;

public final class ArchaiusConfigurationSourceList implements ConfigurationSourceList {

    private final List<AbstractConfiguration> configurations;

    private ArchaiusConfigurationSourceList(List<AbstractConfiguration> configurations) {
        if (configurations == null) {
            throw new IllegalArgumentException("configurations are required");
        }

        this.configurations = Collections.unmodifiableList(new ArrayList<AbstractConfiguration>(configurations));
    }

    public static ConfigurationSourceList of(List<AbstractConfiguration> configurations) {
        return new ArchaiusConfigurationSourceList(configurations);
    }

    @Override
    public void disableDelimiterParsing() {
        for (AbstractConfiguration config : configurations) {
            config.setDelimiterParsingDisabled(true);
        }
    }

    @Override
    public boolean hasLazyJdbcSource() {
        for (AbstractConfiguration config : configurations) {
            if (ArchaiusUtil.isLazyJdbcSource(config)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void attachJdbcSources(DataSource dataSource, String query, String keyColumnName, String valueColumnName) {
        for (AbstractConfiguration config : configurations) {
            ArchaiusUtil.setJdbcConfigurationSource(config, dataSource, query, keyColumnName, valueColumnName);
        }
    }

    @Override
    public void replace(ConfigurationStack stack) {
        stack.clear();
        for (AbstractConfiguration config : configurations) {
            stack.add(config);
        }
    }

}
