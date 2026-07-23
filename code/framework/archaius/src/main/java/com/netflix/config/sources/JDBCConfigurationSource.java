package com.netflix.config.sources;

import com.netflix.config.PollResult;
import com.netflix.config.PolledConfigurationSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

public class JDBCConfigurationSource implements PolledConfigurationSource {

    private final DataSource datasource;
    private final String query;
    private final String keyColumnName;
    private final String valueColumnName;

    public JDBCConfigurationSource(DataSource datasource, String query, String keyColumnName, String valueColumnName) {
        if (datasource == null) {
            throw new IllegalArgumentException("datasource is required");
        }
        this.datasource = datasource;
        this.query = query;
        this.keyColumnName = keyColumnName;
        this.valueColumnName = valueColumnName;
    }

    @Override
    public PollResult poll(boolean initial, Object checkPoint) throws Exception {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            connection = datasource.getConnection();
            statement = connection.createStatement();
            resultSet = statement.executeQuery(query);
            while (resultSet.next()) {
                String key = resultSet.getString(keyColumnName);
                if (key != null) {
                    values.put(key, resultSet.getString(valueColumnName));
                }
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
            if (statement != null) {
                statement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }
        return PollResult.createFull(values);
    }

    public DataSource getDatasource() {
        return datasource;
    }

}
