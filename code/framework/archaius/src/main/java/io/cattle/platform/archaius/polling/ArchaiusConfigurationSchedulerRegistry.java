package io.cattle.platform.archaius.polling;

import io.cattle.platform.archaius.util.ArchaiusUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArchaiusConfigurationSchedulerRegistry implements ConfigurationSchedulerRegistry {

    private final List<RefreshableFixedDelayPollingScheduler> schedulers;

    private ArchaiusConfigurationSchedulerRegistry(List<RefreshableFixedDelayPollingScheduler> schedulers) {
        if (schedulers == null) {
            throw new IllegalArgumentException("schedulers are required");
        }

        this.schedulers = Collections.unmodifiableList(
                new ArrayList<RefreshableFixedDelayPollingScheduler>(schedulers));
    }

    public static ConfigurationSchedulerRegistry of(List<RefreshableFixedDelayPollingScheduler> schedulers) {
        return new ArchaiusConfigurationSchedulerRegistry(schedulers);
    }

    @Override
    public void refreshAndRegister() {
        refreshAll();
        ArchaiusUtil.addSchedulers(schedulers);
    }

    private void refreshAll() {
        for (RefreshableFixedDelayPollingScheduler scheduler : schedulers) {
            scheduler.refresh();
        }
    }

}
