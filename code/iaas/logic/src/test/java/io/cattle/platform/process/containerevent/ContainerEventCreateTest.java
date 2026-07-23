package io.cattle.platform.process.containerevent;

import static io.cattle.platform.core.constants.ContainerEventConstants.CONTAINER_EVENT_SYNC_LABELS;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ContainerEventCreateTest {

    private static final String LABEL_KEY = "io.rancher.container.uuid";
    private static final String ENV_PREFIX = "RANCHER_UUID=";

    ContainerEventCreate handler = new ContainerEventCreate();

    @Test
    public void dataLabelOverridesInspectAndEnvValues() {
        Map<String, Object> dataLabels = new HashMap<String, Object>();
        dataLabels.put(LABEL_KEY, "data-uuid");

        assertEquals("data-uuid", handler.getLabel(LABEL_KEY, ENV_PREFIX,
                inspect("inspect-uuid", "RANCHER_UUID=env-uuid"), data(dataLabels)));
    }

    @Test
    public void inspectLabelIsUsedBeforeEnvFallback() {
        assertEquals("inspect-uuid", handler.getLabel(LABEL_KEY, ENV_PREFIX,
                inspect("inspect-uuid", "RANCHER_UUID=env-uuid"), data(new HashMap<String, Object>())));
    }

    @Test
    public void envValueIsUsedWhenLabelsAreMissing() {
        assertEquals("env-uuid", handler.getLabel(LABEL_KEY, ENV_PREFIX,
                inspect((String) null, "RANCHER_UUID=env-uuid"), data(new HashMap<String, Object>())));
    }

    @Test(expected = ClassCastException.class)
    public void inspectLabelMustRemainAString() {
        Map<String, Object> labels = new HashMap<String, Object>();
        labels.put(LABEL_KEY, Boolean.TRUE);

        handler.getLabel(LABEL_KEY, ENV_PREFIX, inspect(labels, "RANCHER_UUID=env-uuid"),
                data(new HashMap<String, Object>()));
    }

    @Test(expected = ClassCastException.class)
    public void envEntriesMustRemainStrings() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("Env", Arrays.<Object>asList(Boolean.TRUE));

        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("Config", config);

        handler.getLabel(LABEL_KEY, ENV_PREFIX, inspect, data(new HashMap<String, Object>()));
    }

    private Map<String, Object> data(Map<String, Object> labels) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put(CONTAINER_EVENT_SYNC_LABELS, labels);
        return data;
    }

    private Map<String, Object> inspect(String label, String env) {
        Map<String, Object> labels = new HashMap<String, Object>();
        if (label != null) {
            labels.put(LABEL_KEY, label);
        }
        return inspect(labels, env);
    }

    private Map<String, Object> inspect(Map<String, Object> labels, String env) {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("Labels", labels);
        config.put("Env", Arrays.asList(env));

        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("Config", config);
        return inspect;
    }
}
