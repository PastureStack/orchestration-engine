package io.cattle.platform.jmx;

public interface JmxPublisherStatsMBean {

    long getCollectedMetricsCount();

    long getCollectionCount();

    long getCollectionDurationInNanos();

    long getExportCount();

    long getExportDurationInNanos();

    long getExportedMetricsCount();
}
