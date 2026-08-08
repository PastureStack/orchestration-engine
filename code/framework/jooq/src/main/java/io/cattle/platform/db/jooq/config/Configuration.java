package io.cattle.platform.db.jooq.config;

import io.cattle.platform.archaius.util.ArchaiusUtil;

import java.util.List;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;

import org.jooq.ConnectionProvider;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.jooq.jpa.extensions.DefaultAnnotatedPojoMemberProvider;

public class Configuration extends DefaultConfiguration {

    private static final long serialVersionUID = -726368732372005280L;

    String name;
    DataSource dataSource;
    ConnectionProvider connectionProvider;
    List<ExecuteListener> listeners;
    Settings settings = new Settings();

    @PostConstruct
    public void init() {
        String prop = "db." + name + ".database";
        String database = ArchaiusUtil.getStringProperty(prop).get();
        if (database == null) {
            throw new IllegalStateException("Failed to find config for [" + prop + "]");
        }

        try {
            SQLDialect dialect = SQLDialect.valueOf(database.trim().toUpperCase());
            set(dialect);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid SQLDialect [" + database.toUpperCase() + "]", e);
        }

        set(new DefaultAnnotatedPojoMemberProvider());

        if (connectionProvider == null) {
            set(new AutoCommitConnectionProvider(dataSource));
        } else {
            set(connectionProvider);
        }

        settings.setRenderSchema(false);

        String renderNameStyle = ArchaiusUtil.getStringProperty("db." + name + "." + database + ".render.name.style").get();
        if (renderNameStyle != null) {
            applyRenderNameStyle(settings, renderNameStyle);
        }

        set(settings);

        if (listeners != null && listeners.size() > 0) {
            settings().setExecuteLogging(false);
            set(DefaultExecuteListenerProvider.providers(listeners.toArray(new ExecuteListener[listeners.size()])));
        }
    }

    static void applyRenderNameStyle(Settings settings, String renderNameStyle) {
        switch (renderNameStyle.trim().toUpperCase()) {
        case "QUOTED":
            settings.setRenderQuotedNames(RenderQuotedNames.ALWAYS);
            settings.setRenderNameCase(RenderNameCase.AS_IS);
            break;
        case "AS_IS":
            settings.setRenderQuotedNames(RenderQuotedNames.NEVER);
            settings.setRenderNameCase(RenderNameCase.AS_IS);
            break;
        case "LOWER":
            settings.setRenderQuotedNames(RenderQuotedNames.NEVER);
            settings.setRenderNameCase(RenderNameCase.LOWER);
            break;
        case "UPPER":
            settings.setRenderQuotedNames(RenderQuotedNames.NEVER);
            settings.setRenderNameCase(RenderNameCase.UPPER);
            break;
        default:
            throw new IllegalArgumentException("Invalid render.name.style [" + renderNameStyle + "]");
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setManagedDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    public void setManagedConnectionProvider(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public List<ExecuteListener> getListeners() {
        return listeners;
    }

    public void setListeners(List<ExecuteListener> listeners) {
        this.listeners = listeners;
    }

    public Settings getSettings() {
        return settings;
    }

    public void setManagedSettings(Settings settings) {
        this.settings = settings;
    }

}
