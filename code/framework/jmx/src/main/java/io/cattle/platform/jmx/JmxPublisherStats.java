package io.cattle.platform.jmx;

import java.util.concurrent.atomic.AtomicLong;

public class JmxPublisherStats implements JmxPublisherStatsMBean {

    private final AtomicLong collectedMetricsCount = new AtomicLong();
    private final AtomicLong collectionCount = new AtomicLong();
    private final AtomicLong collectionDurationInNanos = new AtomicLong();
    private final AtomicLong exportCount = new AtomicLong();
    private final AtomicLong exportDurationInNanos = new AtomicLong();
    private final AtomicLong exportedMetricsCount = new AtomicLong();

    void collected(int metrics, long durationInNanos) {
        collectedMetricsCount.addAndGet(metrics);
        collectionCount.incrementAndGet();
        collectionDurationInNanos.addAndGet(durationInNanos);
    }

    void exported(int metrics, long durationInNanos) {
        exportedMetricsCount.addAndGet(metrics);
        exportCount.incrementAndGet();
        exportDurationInNanos.addAndGet(durationInNanos);
    }

    @Override
    public long getCollectedMetricsCount() {
        return collectedMetricsCount.get();
    }

    @Override
    public long getCollectionCount() {
        return collectionCount.get();
    }

    @Override
    public long getCollectionDurationInNanos() {
        return collectionDurationInNanos.get();
    }

    @Override
    public long getExportCount() {
        return exportCount.get();
    }

    @Override
    public long getExportDurationInNanos() {
        return exportDurationInNanos.get();
    }

    @Override
    public long getExportedMetricsCount() {
        return exportedMetricsCount.get();
    }
}
