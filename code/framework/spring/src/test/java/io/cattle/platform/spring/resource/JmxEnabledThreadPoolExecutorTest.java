package io.cattle.platform.spring.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.management.ObjectName;

import org.junit.Test;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;

public class JmxEnabledThreadPoolExecutorTest {

    @Test
    public void exposesObjectNameManagedAttributesAndPoolSetters() throws Exception {
        ObjectName objectName = new ObjectName("java.util.concurrent:type=ThreadPoolExecutor,name=testPool");
        SpringConfigurableExecutorService executor = new SpringConfigurableExecutorService("testPool", 1, 2, 30,
                TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2), daemonFactory("test-pool-"),
                new ThreadPoolExecutor.AbortPolicy(), objectName);

        try {
            assertEquals("testPool", executor.getName());
            assertEquals(objectName, executor.getObjectName());
            assertEquals(2, executor.getQueueRemainingCapacity());
            assertNotNull(JmxEnabledThreadPoolExecutor.class.getAnnotation(ManagedResource.class));
            assertNotNull(JmxEnabledThreadPoolExecutor.class.getMethod("getRejectedExecutionCount")
                    .getAnnotation(ManagedAttribute.class));

            executor.setMaximumPoolSize(3);
            executor.setCorePoolSize(2);

            assertEquals(2, executor.getCorePoolSize());
            assertEquals(3, executor.getMaximumPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void countsRejectedExecutionsWithoutChangingTheDelegatePolicy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JmxEnabledThreadPoolExecutor executor = new JmxEnabledThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1), daemonFactory("reject-test-"), new ThreadPoolExecutor.AbortPolicy(),
                new ObjectName("java.util.concurrent:type=ThreadPoolExecutor,name=rejectTest"));

        try {
            executor.execute(blockingTask(started, release));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            executor.execute(noopTask());

            try {
                executor.execute(noopTask());
                fail("Expected third task to be rejected after worker and queue are full");
            } catch (RejectedExecutionException expected) {
                assertEquals(1, executor.getRejectedExecutionCount());
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, prefix + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static Runnable blockingTask(final CountDownLatch started, final CountDownLatch release) {
        return new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private static Runnable noopTask() {
        return new Runnable() {
            @Override
            public void run() {
            }
        };
    }
}
