package io.cattle.platform.docker.storage.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import io.cattle.platform.core.model.tables.records.GenericObjectRecord;
import io.cattle.platform.object.util.DataAccessor;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class PullTaskCreateTest {

    @Test
    public void labelsAreCopiedThroughStringKeyValueBoundary() {
        GenericObjectRecord task = new GenericObjectRecord();
        task.setData(new HashMap<String, Object>());

        Map<String, Object> labels = new HashMap<String, Object>();
        labels.put("io.rancher.scheduler.affinity:host_label", "zone=west");
        labels.put("io.rancher.container.pull_image", "always");
        DataAccessor.setField(task, PullTaskCreate.LABELS, labels);

        Map<String, String> result = new PullTaskCreate().getLabels(task);

        assertNotSame(labels, result);
        assertEquals("zone=west", result.get("io.rancher.scheduler.affinity:host_label"));
        assertEquals("always", result.get("io.rancher.container.pull_image"));
    }

    @Test
    public void missingLabelsRemainNullForExistingDefaultPath() {
        GenericObjectRecord task = new GenericObjectRecord();
        task.setData(new HashMap<String, Object>());

        assertNull(new PullTaskCreate().getLabels(task));
    }
}
