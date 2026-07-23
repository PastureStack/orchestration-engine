package io.cattle.iaas.healthcheck.service.impl;

import static io.cattle.platform.core.util.SystemLabels.LABEL_HEALTHCHECK_DEPLOY_STRATEGY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class HealthcheckServiceImplTest {

    @Test
    public void healthcheckDeployStrategyReturnsNullForMissingData() {
        assertNull(HealthcheckServiceImpl.healthcheckDeployStrategy(null));
    }

    @Test
    public void healthcheckDeployStrategyReturnsNullForMissingLabel() {
        assertNull(HealthcheckServiceImpl.healthcheckDeployStrategy(new LinkedHashMap<String, Object>()));
    }

    @Test
    public void healthcheckDeployStrategyReadsStringLabel() {
        assertEquals("skip", HealthcheckServiceImpl.healthcheckDeployStrategy(hostData("skip")));
    }

    @Test(expected = ClassCastException.class)
    public void healthcheckDeployStrategyRejectsNonStringLabel() {
        HealthcheckServiceImpl.healthcheckDeployStrategy(hostData(Boolean.TRUE));
    }

    private Map<String, Object> hostData(Object strategy) {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put(LABEL_HEALTHCHECK_DEPLOY_STRATEGY, strategy);

        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("labels", labels);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("fields", fields);
        return data;
    }
}
