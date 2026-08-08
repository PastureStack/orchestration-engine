package io.cattle.platform.archaius.polling;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.archaius.util.ArchaiusUtil;

import java.util.Arrays;

import org.junit.Test;

public class ArchaiusConfigurationSchedulerRegistryTest {

    @Test
    public void refreshesAndRegistersSchedulers() {
        CountingScheduler scheduler = new CountingScheduler();
        ConfigurationSchedulerRegistry registry = ArchaiusConfigurationSchedulerRegistry.of(Arrays.asList(scheduler));

        registry.refreshAndRegister();
        assertEquals(1, scheduler.refreshCount);

        ArchaiusUtil.refresh();
        assertEquals(2, scheduler.refreshCount);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSchedulers() {
        ArchaiusConfigurationSchedulerRegistry.of(null);
    }

    private static class CountingScheduler extends RefreshableFixedDelayPollingScheduler {

        int refreshCount;

        @Override
        public void refresh() {
            refreshCount++;
        }

    }

}
