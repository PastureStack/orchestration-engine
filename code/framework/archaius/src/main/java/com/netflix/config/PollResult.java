package com.netflix.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PollResult {

    private final Map<String, Object> complete;

    private PollResult(Map<String, Object> complete) {
        this.complete = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(complete));
    }

    public static PollResult createFull(Map<String, Object> complete) {
        return new PollResult(complete);
    }

    public Map<String, Object> getComplete() {
        return complete;
    }

}
