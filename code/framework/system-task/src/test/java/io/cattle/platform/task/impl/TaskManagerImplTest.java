package io.cattle.platform.task.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.task.Task;
import io.cattle.platform.task.dao.TaskDao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class TaskManagerImplTest {

    @Test
    public void scheduleReadsDynamicConfigThroughWrapper() {
        final String scheduleKey = "task.config.item.sync.schedule";
        final String delayKey = "task.initial.delay.seconds";

        try {
            ConfigurationManager.getConfigInstance().setProperty(scheduleKey, "5");
            ConfigurationManager.getConfigInstance().setProperty(delayKey, "2");

            RecordingScheduler scheduler = new RecordingScheduler();
            RecordingTaskDao taskDao = new RecordingTaskDao();
            Task task = new NamedTask("config.item.sync");

            TaskManagerImpl manager = new TaskManagerImpl();
            manager.executorService = scheduler;
            manager.taskDao = taskDao;
            manager.tasks = Collections.singletonList(task);
            manager.schedule(true, task);

            assertEquals(Collections.singletonList("config.item.sync"), taskDao.names);
            assertEquals(1, scheduler.scheduleWithFixedDelayCalls);
            assertEquals(2000L, scheduler.initialDelay);
            assertEquals(5000L, scheduler.delay);
            assertSame(TimeUnit.MILLISECONDS, scheduler.unit);
        } finally {
            clearProperty(scheduleKey);
            clearProperty(delayKey);
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static class NamedTask implements Task {

        private final String name;

        NamedTask(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void run() {
        }
    }

    private static class RecordingTaskDao implements TaskDao {

        private final List<String> names = new ArrayList<String>();

        @Override
        public void register(String name) {
            names.add(name);
        }
    }

    private static class RecordingScheduler extends AbstractExecutorService implements ScheduledExecutorService {

        int scheduleWithFixedDelayCalls;
        long initialDelay;
        long delay;
        TimeUnit unit;

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            this.scheduleWithFixedDelayCalls++;
            this.initialDelay = initialDelay;
            this.delay = delay;
            this.unit = unit;
            return new NoOpScheduledFuture<Object>();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static class NoOpScheduledFuture<V> implements ScheduledFuture<V> {

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}
