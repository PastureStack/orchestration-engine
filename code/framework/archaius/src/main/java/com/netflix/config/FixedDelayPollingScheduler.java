package com.netflix.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FixedDelayPollingScheduler extends AbstractPollingScheduler {

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    private final int initialDelayMillis;
    private final int delayMillis;
    private final boolean ignoreDeletesFromSource;
    private final ScheduledExecutorService executor;

    public FixedDelayPollingScheduler() {
        this(30000, 60000, false);
    }

    public FixedDelayPollingScheduler(int initialDelayMillis, int delayMillis, boolean ignoreDeletesFromSource) {
        this.initialDelayMillis = initialDelayMillis;
        this.delayMillis = delayMillis;
        this.ignoreDeletesFromSource = ignoreDeletesFromSource;
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "rc16-config-poller-" + THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    protected synchronized void schedule(Runnable runnable) {
        executor.scheduleWithFixedDelay(runnable, initialDelayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    public boolean isIgnoreDeletesFromSource() {
        return ignoreDeletesFromSource;
    }

}
