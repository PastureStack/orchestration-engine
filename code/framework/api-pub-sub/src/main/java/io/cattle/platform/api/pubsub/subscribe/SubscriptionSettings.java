package io.cattle.platform.api.pubsub.subscribe;

interface SubscriptionSettings {

    long pingIntervalMillis();

    int maxPings();
}
