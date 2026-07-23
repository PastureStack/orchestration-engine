package com.netflix.config;

import org.apache.commons.configuration.AbstractConfiguration;

public class DynamicConfiguration extends AbstractConfiguration {

    private volatile PolledConfigurationSource source;
    private volatile Object checkPoint;

    public DynamicConfiguration() {
    }

    public DynamicConfiguration(PolledConfigurationSource source, AbstractPollingScheduler scheduler) {
        this.source = source;
        if (scheduler != null) {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    }

    public PolledConfigurationSource getSource() {
        return source;
    }

    public void setSource(PolledConfigurationSource source) {
        this.source = source;
        refresh();
    }

    public void refresh() {
        PolledConfigurationSource currentSource = source;
        if (currentSource == null) {
            return;
        }
        try {
            PollResult result = currentSource.poll(false, checkPoint);
            if (result != null) {
                replaceAll(result.getComplete());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to poll dynamic configuration source", e);
        }
    }

}
