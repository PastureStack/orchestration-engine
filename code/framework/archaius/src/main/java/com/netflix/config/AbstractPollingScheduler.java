package com.netflix.config;

public abstract class AbstractPollingScheduler {

    protected abstract void schedule(Runnable runnable);

}
