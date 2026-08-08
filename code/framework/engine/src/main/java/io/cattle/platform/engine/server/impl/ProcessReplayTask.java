package io.cattle.platform.engine.server.impl;

import io.cattle.platform.engine.server.ProcessServer;
import io.cattle.platform.task.Task;

import jakarta.inject.Inject;

public class ProcessReplayTask implements Task {

    @Inject
    ProcessServer processServer;

    @Override
    public void run() {
        processServer.runOutstandingJobs();
    }

    @Override
    public String getName() {
        return "process.replay";
    }

}
