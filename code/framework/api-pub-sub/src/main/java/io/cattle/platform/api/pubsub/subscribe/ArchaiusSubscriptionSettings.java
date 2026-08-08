package io.cattle.platform.api.pubsub.subscribe;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusSubscriptionSettings implements SubscriptionSettings {

    private final ConfigProperty<Long> pingInterval = ArchaiusUtil.getLongProperty("api.sub.ping.interval.millis");
    private final ConfigProperty<Integer> maxPings = ArchaiusUtil.getIntProperty("api.sub.max.pings");

    static SubscriptionSettings create() {
        return new ArchaiusSubscriptionSettings();
    }

    @Override
    public long pingIntervalMillis() {
        return pingInterval.get();
    }

    @Override
    public int maxPings() {
        return maxPings.get();
    }
}
