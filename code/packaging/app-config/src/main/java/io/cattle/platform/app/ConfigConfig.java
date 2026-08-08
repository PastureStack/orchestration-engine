package io.cattle.platform.app;

import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;
import io.cattle.platform.archaius.sources.ArchaiusConfigFactory;
import io.cattle.platform.archaius.startup.ArchaiusStartup;
import io.cattle.platform.archaius.startup.ArchaiusConfigRegistration;
import io.cattle.platform.datasource.DataSourceFactory;
import io.cattle.platform.extension.dynamic.DynamicExtensionManager;
import io.cattle.platform.extension.impl.ExtensionManagerImpl;
import io.cattle.platform.logback.Startup;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
public class ConfigConfig {

    private static final String[] DEFAULTS = new String[] {
        "META-INF/cattle/agent-server/defaults.properties",
        "META-INF/cattle/system-services/defaults.properties",
        "META-INF/cattle/defaults/defaults.properties",
        "META-INF/cattle/system/defaults.properties",
        "META-INF/cattle/redis/defaults.properties",
        "META-INF/cattle/core-model/defaults.properties",
        "META-INF/cattle/process/defaults.properties",
        "META-INF/cattle/encryption/defaults.properties",
        "META-INF/cattle/allocator-server/defaults.properties",
        "META-INF/cattle/core-object-defaults/defaults.properties",
        "META-INF/cattle/bootstrap/defaults.properties",
        "META-INF/cattle/iaas-api/defaults.properties",
        "META-INF/cattle/config-defaults/defaults.properties",
        "META-INF/cattle/api-server/defaults.properties",
        "META-INF/cattle/defaults/dev-defaults.properties",
        "META-INF/cattle/system-services/healthcheck-defaults.properties",
    };

    @Bean
    ArchaiusStartup ArchaiusStartup(ExtensionManagerImpl em, @Qualifier("GlobalProperties") Properties props,
            DataSourceFactory dsf, RefreshableFixedDelayPollingScheduler scheduler) {
        ArchaiusStartup.setGlobalDefaults(props);
        ArchaiusStartup startup = new ArchaiusStartup();
        startup.setExtensionManager(em);
        startup.setDataSourceFactory(dsf);
        startup.setSchedulers(Arrays.asList(scheduler));

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.defaultEnvironment(), "DefaultEnvironmentConfig");
        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.environment(), "EnvironmentConfig");
        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.systemProperties(), "SystemConfig");

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.optionalProperties("cattle-local.properties"),
                "CattleLocalFileConfig");

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.database(scheduler, "Database"),
                "DatabaseConfig");

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.optionalProperties("cattle.properties"),
                "CattleFileConfig");

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.optionalProperties("cattle-override.properties"),
                "CattleOverrideFileConfig");

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.optionalProperties("cattle-global.properties"),
                "CattleGlobalFileConfig");

        ArchaiusConfigRegistration.addConfig(em, ArchaiusConfigFactory.defaults(props, "Code Packaged Defaults"),
                "DefaultsConfig");

        startup.init();
        em.start();
        startup.start();

        return startup;
    }

    @Bean
    RefreshableFixedDelayPollingScheduler ConfigScheduler() {
        return new RefreshableFixedDelayPollingScheduler();
    }

    @Bean
    ExtensionManagerImpl extensionManager() {
        return new DynamicExtensionManager();
    }

    @Bean
    Properties GlobalProperties() throws IOException {
        Properties props = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        for (String config : DEFAULTS) {
            try (InputStream is = cl.getResourceAsStream(config)) {
                if (is != null) {
                    props.load(is);
                }
            }
        }

        return props;
    }

    @Bean
    @DependsOn("ArchaiusStartup")
    Startup Startup() {
        return new Startup();
    }

}
