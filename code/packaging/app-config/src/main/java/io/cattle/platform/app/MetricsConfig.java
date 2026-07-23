package io.cattle.platform.app;

import io.cattle.platform.metrics.util.MetricsStartup;
import io.cattle.platform.metrics.util.MetricsUtil;

import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;


@Configuration
public class MetricsConfig {

    @Bean
    SmartLifecycle MetricsStartup() {
        MetricsStartup startup = new MetricsStartup();

        return new SmartLifecycle() {
            @Override
            public void start() {
                startup.start();
            }

            @Override
            public void stop() {
                startup.stop();
            }

            @Override
            public void stop(Runnable callback) {
                try {
                    stop();
                } finally {
                    callback.run();
                }
            }

            @Override
            public boolean isRunning() {
                return startup.isRunning();
            }

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public int getPhase() {
                return 0;
            }
        };
    }

    @Bean
    MetricRegistry MetricsRegistry() {
        return MetricsUtil.getRegistry();
    }

    @Bean
    HealthCheckRegistry HealthCheckRegistry() {
        return MetricsUtil.getHealthCheckRegistry();
    }

}
