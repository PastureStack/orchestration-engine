package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Secret;
import io.cattle.platform.core.model.tables.records.SecretRecord;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.servicediscovery.api.resource.ServiceDiscoveryConfigItem;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryApiServiceImplComposeOptionsTest {

    @Test
    public void populateLogConfigFormatsDriverAndOptions() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> config = new LinkedHashMap<>();
        config.put("max-size", "10m");
        Map<Object, Object> logConfig = new LinkedHashMap<>();
        logConfig.put("driver", "json-file");
        logConfig.put("config", config);
        cattleServiceData.put(ServiceConstants.FIELD_LOG_CONFIG, logConfig);
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePrivateMapMethod("populateLogConfig", cattleServiceData, composeServiceData);

        Map<?, ?> logging = Map.class.cast(composeServiceData.get("logging"));
        assertEquals("json-file", logging.get("driver"));
        assertEquals(config, logging.get("options"));
    }

    @Test
    public void populateLogConfigSkipsWhenDriverIsMissing() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> config = new LinkedHashMap<>();
        config.put("max-file", "3");
        Map<Object, Object> logConfig = new LinkedHashMap<>();
        logConfig.put("config", config);
        cattleServiceData.put(ServiceConstants.FIELD_LOG_CONFIG, logConfig);
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePrivateMapMethod("populateLogConfig", cattleServiceData, composeServiceData);

        assertFalse(composeServiceData.containsKey("logging"));
    }

    @Test(expected = ClassCastException.class)
    public void populateLogConfigRejectsNonStringKeys() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> logConfig = new LinkedHashMap<>();
        logConfig.put(Integer.valueOf(1), "json-file");
        cattleServiceData.put(ServiceConstants.FIELD_LOG_CONFIG, logConfig);

        invokePrivateMapMethod("populateLogConfig", cattleServiceData, new HashMap<String, Object>());
    }

    @Test
    public void populateTmpfsFormatsMountOptions() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> tmpfs = new LinkedHashMap<>();
        tmpfs.put("/run", "");
        tmpfs.put("/cache", "size=64m");
        cattleServiceData.put(ServiceConstants.FIELD_TMPFS, tmpfs);
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePrivateMapMethod("populateTmpfs", cattleServiceData, composeServiceData);

        assertEquals(Arrays.asList("/run", "/cache:size=64m"), composeServiceData.get("tmpfs"));
    }

    @Test(expected = ClassCastException.class)
    public void populateTmpfsRejectsNonStringKeys() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> tmpfs = new LinkedHashMap<>();
        tmpfs.put(Integer.valueOf(1), "");
        cattleServiceData.put(ServiceConstants.FIELD_TMPFS, tmpfs);

        invokePrivateMapMethod("populateTmpfs", cattleServiceData, new HashMap<String, Object>());
    }

    @Test
    public void populateBlkioOptionsFormatsDeviceMaps() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> device = new LinkedHashMap<>();
        device.put("weight", Integer.valueOf(500));
        device.put("readIops", Long.valueOf(1000));
        device.put("writeBps", Long.valueOf(2048));
        Map<Object, Object> blkio = new LinkedHashMap<>();
        blkio.put("/dev/sda", device);
        blkio.put("/dev/sdb", "skip");
        cattleServiceData.put(ServiceConstants.FIELD_BLKIOOPTIONS, blkio);
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePrivateMapMethod("populateBlkioOptions", cattleServiceData, composeServiceData);

        Map<?, ?> deviceWeight = Map.class.cast(composeServiceData.get("blkio_weight_device"));
        Map<?, ?> deviceReadIops = Map.class.cast(composeServiceData.get("device_read_iops"));
        Map<?, ?> deviceWriteBps = Map.class.cast(composeServiceData.get("device_write_bps"));
        assertEquals(Integer.valueOf(500), deviceWeight.get("/dev/sda"));
        assertEquals(Long.valueOf(1000), deviceReadIops.get("/dev/sda"));
        assertEquals(Long.valueOf(2048), deviceWriteBps.get("/dev/sda"));
        assertFalse(composeServiceData.containsKey("device_read_bps"));
        assertFalse(composeServiceData.containsKey("device_write_iops"));
    }

    @Test(expected = ClassCastException.class)
    public void populateBlkioOptionsRejectsNonStringKeys() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> blkio = new LinkedHashMap<>();
        blkio.put(Integer.valueOf(1), new HashMap<Object, Object>());
        cattleServiceData.put(ServiceConstants.FIELD_BLKIOOPTIONS, blkio);

        invokePrivateMapMethod("populateBlkioOptions", cattleServiceData, new HashMap<String, Object>());
    }

    @Test
    public void populateUlimitFormatsSoftAndHardLimits() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        Map<Object, Object> softOnly = new LinkedHashMap<>();
        softOnly.put("name", "nofile");
        softOnly.put("soft", Long.valueOf(1024));
        Map<Object, Object> softAndHard = new LinkedHashMap<>();
        softAndHard.put("name", "nproc");
        softAndHard.put("soft", Integer.valueOf(10));
        softAndHard.put("hard", Integer.valueOf(20));
        cattleServiceData.put(ServiceConstants.FIELD_ULIMITS, Arrays.asList(softOnly, softAndHard, "skip"));
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePrivateMapMethod("populateUlimit", cattleServiceData, composeServiceData);

        Map<?, ?> ulimits = Map.class.cast(composeServiceData.get("ulimits"));
        assertEquals(Long.valueOf(1024), ulimits.get("nofile"));
        Map<?, ?> nproc = Map.class.cast(ulimits.get("nproc"));
        assertEquals(Integer.valueOf(10), nproc.get("soft"));
        assertEquals(Integer.valueOf(20), nproc.get("hard"));
    }

    @Test(expected = NullPointerException.class)
    public void populateUlimitKeepsMissingNameFailure() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        cattleServiceData.put(ServiceConstants.FIELD_ULIMITS, Arrays.asList(new HashMap<Object, Object>()));

        invokePrivateMapMethod("populateUlimit", cattleServiceData, new HashMap<String, Object>());
    }

    @Test
    public void translateV1VolumesToV2CreatesExternalNamedVolumes() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        cattleServiceData.put(ServiceDiscoveryConfigItem.VOLUME_DRIVER.getCattleName(), "rancher-nfs");
        cattleServiceData.put(InstanceConstants.FIELD_DATA_VOLUMES,
                Arrays.asList("named:/data", "/host:/host", "anonymous", "existing:/already"));
        Map<String, Object> composeServiceData = new HashMap<>();
        composeServiceData.put(ServiceDiscoveryConfigItem.VOLUME_DRIVER.getDockerName(), "rancher-nfs");
        Map<String, Object> volumesData = new HashMap<>();
        Map<String, Object> existing = new HashMap<>();
        volumesData.put("existing", existing);

        invokePrivateVolumeMethod("translateV1VolumesToV2", cattleServiceData, composeServiceData, volumesData);

        assertFalse(composeServiceData.containsKey(ServiceDiscoveryConfigItem.VOLUME_DRIVER.getDockerName()));
        Map<?, ?> named = Map.class.cast(volumesData.get("named"));
        assertEquals(Boolean.TRUE, named.get("external"));
        assertEquals("rancher-nfs", named.get("driver"));
        assertEquals(existing, volumesData.get("existing"));
        assertFalse(volumesData.containsKey("/host"));
        assertFalse(volumesData.containsKey("anonymous"));
    }

    @Test(expected = ClassCastException.class)
    public void translateV1VolumesToV2RejectsNonStringVolumeSpecs() throws Exception {
        Map<String, Object> cattleServiceData = new HashMap<>();
        cattleServiceData.put(ServiceDiscoveryConfigItem.VOLUME_DRIVER.getCattleName(), "rancher-nfs");
        cattleServiceData.put(InstanceConstants.FIELD_DATA_VOLUMES, Arrays.asList(Integer.valueOf(1)));

        invokePrivateVolumeMethod("translateV1VolumesToV2", cattleServiceData, new HashMap<String, Object>(),
                new HashMap<String, Object>());
    }

    @Test
    public void populateSecretsFormatsShortAndLongSyntax() throws Exception {
        Map<Object, Object> shortSecret = new LinkedHashMap<>();
        shortSecret.put("secretId", "1");
        Map<Object, Object> longSecret = new LinkedHashMap<>();
        longSecret.put("secretId", "2");
        longSecret.put("name", "server.key");
        longSecret.put("uid", "1000");
        longSecret.put("gid", "1000");
        longSecret.put("mode", "400");
        Map<Object, Object> missingSecret = new LinkedHashMap<>();
        missingSecret.put("secretId", "999");
        Map<String, Object> cattleServiceData = new HashMap<>();
        cattleServiceData.put(ServiceConstants.FIELD_SECRETS,
                Arrays.asList(shortSecret, longSecret, "skip", missingSecret));
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<String, Object> secretsData = new HashMap<>();

        invokePrivateSecretsMethod(cattleServiceData, composeServiceData, secretsData);

        List<?> entries = List.class.cast(composeServiceData.get("secrets"));
        assertEquals("db-password", entries.get(0));
        Map<?, ?> longEntry = Map.class.cast(entries.get(1));
        assertEquals("tls-key", longEntry.get("source"));
        assertEquals("server.key", longEntry.get("target"));
        assertEquals("1000", longEntry.get("uid"));
        assertEquals("1000", longEntry.get("gid"));
        assertEquals("0400", longEntry.get("mode"));
        assertEquals(2, entries.size());
        assertEquals("true", Map.class.cast(secretsData.get("db-password")).get("external"));
        assertEquals("true", Map.class.cast(secretsData.get("tls-key")).get("external"));
        assertFalse(secretsData.containsKey("999"));
    }

    private static void invokePrivateMapMethod(String methodName, Map<String, Object> cattleServiceData,
            Map<String, Object> composeServiceData) throws Exception {
        Method method = ServiceDiscoveryApiServiceImpl.class.getDeclaredMethod(methodName, Map.class, Map.class);
        method.setAccessible(true);
        try {
            method.invoke(newService(), cattleServiceData, composeServiceData);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static void invokePrivateVolumeMethod(String methodName, Map<String, Object> cattleServiceData,
            Map<String, Object> composeServiceData, Map<String, Object> volumesData) throws Exception {
        Method method = ServiceDiscoveryApiServiceImpl.class.getDeclaredMethod(methodName, Map.class, Map.class,
                Map.class);
        method.setAccessible(true);
        try {
            method.invoke(newService(), cattleServiceData, composeServiceData, volumesData);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static void invokePrivateSecretsMethod(Map<String, Object> cattleServiceData,
            Map<String, Object> composeServiceData, Map<String, Object> secretsData) throws Exception {
        Method method = ServiceDiscoveryApiServiceImpl.class.getDeclaredMethod("populateSecrets", Map.class,
                Map.class, Map.class);
        method.setAccessible(true);
        try {
            method.invoke(newServiceWithSecrets(), cattleServiceData, composeServiceData, secretsData);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static ServiceDiscoveryApiServiceImpl newService() {
        ServiceDiscoveryApiServiceImpl service = new ServiceDiscoveryApiServiceImpl();
        service.formatters = Collections.emptyList();
        return service;
    }

    private static ServiceDiscoveryApiServiceImpl newServiceWithSecrets() {
        ServiceDiscoveryApiServiceImpl service = newService();
        service.objectManager = secretObjectManager();
        return service;
    }

    private static ObjectManager secretObjectManager() {
        Map<String, Secret> secrets = new HashMap<>();
        secrets.put("1", secret("db-password"));
        secrets.put("2", secret("tls-key"));
        return ObjectManager.class.cast(Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("loadResource".equals(method.getName()) && args.length == 2 && args[0] == Secret.class) {
                        return secrets.get(String.valueOf(args[1]));
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static Secret secret(String name) {
        SecretRecord secret = new SecretRecord();
        secret.setName(name);
        return secret;
    }
}
