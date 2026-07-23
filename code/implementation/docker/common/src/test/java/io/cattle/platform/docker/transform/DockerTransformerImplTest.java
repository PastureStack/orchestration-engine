package io.cattle.platform.docker.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

import io.cattle.platform.core.addon.BlkioDeviceOption;
import io.cattle.platform.core.addon.LogConfig;
import io.cattle.platform.core.addon.Ulimit;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.object.util.DataAccessor;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class DockerTransformerImplTest {

    @Test
    public void setLabelsPreservesLegacyStringConversion() {
        InstanceRecord instance = new InstanceRecord();
        instance.setData(new HashMap<String, Object>());
        DataAccessor.setField(instance, InstanceConstants.FIELD_LABELS, map("existing", "kept"));

        Map<Object, Object> dockerLabels = new LinkedHashMap<Object, Object>();
        dockerLabels.put("io.rancher.label", "value");
        dockerLabels.put("empty", null);
        dockerLabels.put(42, 7);
        dockerLabels.put(null, "ignored");

        Map<String, Object> config = new HashMap<String, Object>();
        config.put("Labels", dockerLabels);
        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("Config", config);

        new DockerTransformerImpl().setLabels(instance, inspect);

        Map<String, Object> labels = DataAccessor.fieldMap(instance, InstanceConstants.FIELD_LABELS);
        assertEquals("kept", labels.get("existing"));
        assertEquals("value", labels.get("io.rancher.label"));
        assertEquals("", labels.get("empty"));
        assertEquals("7", labels.get("42"));
        assertFalse(labels.containsKey(null));
    }

    @Test
    public void transformMountsPreservesDockerInspectVolumeFields() {
        Map<String, Object> mount = new HashMap<String, Object>();
        mount.put("RW", Boolean.FALSE);
        mount.put("Driver", "local");
        mount.put("Destination", "/container/data");
        mount.put("Source", "/host/data");
        mount.put("Name", "data-volume");

        List<DockerInspectTransformVolume> volumes = new DockerTransformerImpl().transformMounts(
                Collections.<Object>singletonList(mount));

        assertEquals(1, volumes.size());
        DockerInspectTransformVolume volume = volumes.get(0);
        assertEquals("/container/data", volume.getContainerPath());
        assertEquals("file:///host/data", volume.getUri());
        assertEquals("ro", volume.getAccessMode());
        assertFalse(volume.isBindMount());
        assertEquals("local", volume.getDriver());
        assertEquals("data-volume", volume.getName());
        assertEquals("data-volume", volume.getExternalId());
    }

    @Test
    public void rwMapPreservesLegacyVolumePermissions() {
        Map<String, Object> volumeRws = new LinkedHashMap<String, Object>();
        volumeRws.put("/read-write", Boolean.TRUE);
        volumeRws.put("/read-only", Boolean.FALSE);

        Map<String, String> result = new DockerTransformerImpl().rwMap(volumeRws);

        assertEquals("rw", result.get("/read-write"));
        assertEquals("ro", result.get("/read-only"));
    }

    @Test
    public void setBlkioDeviceOptionsReadsDockerInspectMaps() {
        Map<String, Object> readIops = blkioOption("/dev/sda", "Rate", 1000);
        Map<String, Object> writeIops = blkioOption("/dev/sda", "Rate", 2000);
        Map<String, Object> readBps = blkioOption("/dev/sda", "Rate", 3000);
        Map<String, Object> writeBps = blkioOption("/dev/sda", "Rate", 4000);
        Map<String, Object> weight = blkioOption("/dev/sda", "Weight", 500);

        Map<String, Object> hostConfig = new HashMap<String, Object>();
        hostConfig.put("BlkioDeviceReadIOps", Collections.<Object>singletonList(readIops));
        hostConfig.put("BlkioDeviceWriteIOps", Collections.<Object>singletonList(writeIops));
        hostConfig.put("BlkioDeviceReadBps", Collections.<Object>singletonList(readBps));
        hostConfig.put("BlkioDeviceWriteBps", Collections.<Object>singletonList(writeBps));
        hostConfig.put("BlkioWeightDevice", Collections.<Object>singletonList(weight));

        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("HostConfig", hostConfig);

        InstanceRecord instance = new InstanceRecord();
        instance.setData(new HashMap<String, Object>());

        new DockerTransformerImpl().setBlkioDeviceOptionss(instance, inspect);

        Map<?, ?> result = Map.class.cast(DataAccessor.fields(instance)
                .withKey(DockerInstanceConstants.FIELD_BLKIO_DEVICE_OPTIONS).get());
        BlkioDeviceOption option = BlkioDeviceOption.class.cast(result.get("/dev/sda"));
        assertEquals(Integer.valueOf(1000), option.getReadIops());
        assertEquals(Integer.valueOf(2000), option.getWriteIops());
        assertEquals(Integer.valueOf(3000), option.getReadBps());
        assertEquals(Integer.valueOf(4000), option.getWriteBps());
        assertEquals(Integer.valueOf(500), option.getWeight());
    }

    @Test
    public void setLogConfigCopiesDockerInspectStringMap() {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("max-size", "10m");
        options.put("max-file", "3");
        options.put("nullable", null);

        Map<String, Object> logConfig = new HashMap<String, Object>();
        logConfig.put("Type", "json-file");
        logConfig.put("Config", options);

        Map<String, Object> hostConfig = new HashMap<String, Object>();
        hostConfig.put("LogConfig", logConfig);

        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("HostConfig", hostConfig);

        InstanceRecord instance = new InstanceRecord();
        instance.setData(new HashMap<String, Object>());

        new DockerTransformerImpl().setLogConfig(instance, inspect);

        LogConfig result = LogConfig.class.cast(DataAccessor.fields(instance)
                .withKey(InstanceConstants.FIELD_LOG_CONFIG).get());
        assertEquals("json-file", result.getDriver());
        assertEquals("10m", result.getConfig().get("max-size"));
        assertEquals("3", result.getConfig().get("max-file"));
        assertEquals(null, result.getConfig().get("nullable"));
        assertNotSame(options, result.getConfig());
    }

    @Test
    public void setUlimitReadsDockerInspectListWithWildcardMaps() {
        Map<String, Object> nofile = new HashMap<String, Object>();
        nofile.put("Name", "nofile");
        nofile.put("Hard", 65536);
        nofile.put("Soft", 1024);

        Map<String, Object> hostConfig = new HashMap<String, Object>();
        hostConfig.put("Ulimits", Collections.<Object>singletonList(nofile));

        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("HostConfig", hostConfig);

        InstanceRecord instance = new InstanceRecord();
        instance.setData(new HashMap<String, Object>());

        new DockerTransformerImpl().setUlimit(instance, inspect);

        List<?> result = List.class.cast(DataAccessor.fields(instance)
                .withKey(DockerInstanceConstants.FIELD_ULIMITS).get());
        assertEquals(1, result.size());
        Ulimit ulimit = Ulimit.class.cast(result.get(0));
        assertEquals("nofile", ulimit.getName());
        assertEquals(Integer.valueOf(65536), ulimit.getHard());
        assertEquals(Integer.valueOf(1024), ulimit.getSoft());
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put(key, value);
        return result;
    }

    private static Map<String, Object> blkioOption(String path, String valueName, Integer value) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("Path", path);
        result.put(valueName, value);
        return result;
    }
}
