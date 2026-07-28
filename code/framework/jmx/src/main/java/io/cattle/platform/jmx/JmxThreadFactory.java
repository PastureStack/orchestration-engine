package io.cattle.platform.jmx;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class JmxThreadFactory implements ThreadFactory {

    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "rc16-jmx-publisher-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
