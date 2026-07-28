package com.netflix.config;

public interface PolledConfigurationSource {

    PollResult poll(boolean initial, Object checkPoint) throws Exception;

}
