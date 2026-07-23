package io.cattle.platform.spring.resource;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.springframework.core.style.ToStringCreator;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.jmx.export.naming.SelfNaming;

@ManagedResource
public class JmxEnabledThreadPoolExecutor extends ThreadPoolExecutor implements SelfNaming {

    private final CountingRejectedExecutionHandler countingRejectedExecutionHandler;
    private final ObjectName objectName;

    public JmxEnabledThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler rejectedExecutionHandler,
            ObjectName objectName) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory,
                new CountingRejectedExecutionHandler(rejectedExecutionHandler), objectName);
    }

    private JmxEnabledThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, CountingRejectedExecutionHandler rejectedExecutionHandler,
            ObjectName objectName) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, rejectedExecutionHandler);
        this.countingRejectedExecutionHandler = rejectedExecutionHandler;
        this.objectName = objectName;
    }

    @Override
    @ManagedAttribute(description = "Returns the approximate number of threads that are actively executing tasks")
    public int getActiveCount() {
        return super.getActiveCount();
    }

    @Override
    @ManagedAttribute(description = "Returns the approximate total number of tasks that have completed execution.")
    public long getCompletedTaskCount() {
        return super.getCompletedTaskCount();
    }

    @Override
    @ManagedAttribute(description = "Returns the core number of threads")
    public int getCorePoolSize() {
        return super.getCorePoolSize();
    }

    @Override
    @ManagedAttribute(description = "Returns the largest number of threads that have ever simultaneously been in the pool.")
    public int getLargestPoolSize() {
        return super.getLargestPoolSize();
    }

    @Override
    @ManagedAttribute(description = "Returns the maximum allowed number of threads")
    public int getMaximumPoolSize() {
        return super.getMaximumPoolSize();
    }

    @Override
    public ObjectName getObjectName() throws MalformedObjectNameException {
        return objectName;
    }

    @ManagedAttribute(description = "Returns the number of additional elements that this queue can accept without blocking")
    public int getQueueRemainingCapacity() {
        return getQueue().remainingCapacity();
    }

    @ManagedAttribute(description = "Returns the number of tasks that have ever been rejected")
    public int getRejectedExecutionCount() {
        return countingRejectedExecutionHandler.getRejectedExecutionCount();
    }

    @Override
    @ManagedAttribute(description = "Returns the approximate total number of tasks that have ever been scheduled for execution")
    public long getTaskCount() {
        return super.getTaskCount();
    }

    @Override
    @ManagedAttribute(description = "Sets the core number of threads")
    public void setCorePoolSize(int corePoolSize) {
        super.setCorePoolSize(corePoolSize);
    }

    @Override
    @ManagedAttribute(description = "Sets the maximum allowed number of threads")
    public void setMaximumPoolSize(int maximumPoolSize) {
        super.setMaximumPoolSize(maximumPoolSize);
    }

    @Override
    public String toString() {
        return new ToStringCreator(this).append("objectName", objectName)
                .append("corePoolSize", getCorePoolSize())
                .append("maximumPoolSize", getMaximumPoolSize())
                .append("keepAliveTimeInMillis", getKeepAliveTime(TimeUnit.MILLISECONDS))
                .append("queue", getQueue().getClass())
                .append("rejectedExecutionHandler", getRejectedExecutionHandler()).toString();
    }

    private static class CountingRejectedExecutionHandler implements RejectedExecutionHandler {

        private final AtomicInteger rejectedExecutionCount = new AtomicInteger();
        private final RejectedExecutionHandler rejectedExecutionHandler;

        CountingRejectedExecutionHandler(RejectedExecutionHandler rejectedExecutionHandler) {
            if (rejectedExecutionHandler == null) {
                throw new NullPointerException("rejectedExecutionHandler");
            }
            this.rejectedExecutionHandler = rejectedExecutionHandler;
        }

        int getRejectedExecutionCount() {
            return rejectedExecutionCount.get();
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectedExecutionCount.incrementAndGet();
            rejectedExecutionHandler.rejectedExecution(r, executor);
        }

        @Override
        public String toString() {
            return new ToStringCreator(this).append("rejectedExecutionCount", rejectedExecutionCount)
                    .append("rejectedExecutionHandler", rejectedExecutionHandler).toString();
        }
    }
}
