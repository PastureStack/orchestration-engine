package io.cattle.platform.archaius.sources;

import io.cattle.platform.archaius.util.ConfigurationStack;

import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Platform-owned view over the ordered startup configuration source list.
 */
public interface ConfigurationSourceList {

    void disableDelimiterParsing();

    boolean hasLazyJdbcSource();

    void attachJdbcSources(DataSource dataSource, String query, String keyColumnName, String valueColumnName);

    default void attachJdbcSources(Supplier<DataSource> dataSource, String query, String keyColumnName,
            String valueColumnName) {
        if (hasLazyJdbcSource()) {
            attachJdbcSources(dataSource.get(), query, keyColumnName, valueColumnName);
        }
    }

    void replace(ConfigurationStack stack);

}
