package io.cattle.platform.core.util;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class HardwareOptionsTest {
    @Test
    public void acceptsLargeSharedMemoryAndGpuIds() {
        Map<String, Object> config = new HashMap<>();
        config.put("shmSize", 2147483648L);
        config.put("ipcMode", "private");
        config.put("runtime", "nvidia");
        Map<String, Object> request = new HashMap<>();
        request.put("deviceIds", Arrays.asList("GPU-one"));
        request.put("capabilities", Arrays.asList(Arrays.asList("gpu")));
        config.put("deviceRequests", Arrays.asList(request));
        assertNull(HardwareOptions.error(config));
        request.put("count", 1);
        assertNotNull(HardwareOptions.error(config));
        request.remove("count");
        request.put("deviceIds", Arrays.asList("GPU-one", "GPU-one"));
        assertNotNull(HardwareOptions.error(config));
    }
    @Test
    public void rejectsSharedIpcAndPreservesNoHardwareDefaults() {
        Map<String, Object> config = new HashMap<>();
        assertNull(HardwareOptions.error(config));
        config.put("shmSize", 2147483648L);
        config.put("ipcMode", "host");
        assertNotNull(HardwareOptions.error(config));
        assertEquals(2147483648L, config.get("shmSize"));
    }
    @Test
    public void apiRejectsInvalidLimitsAndMapsEvenWithoutGpuFields() {
        for (Map<String, Object> config : Arrays.<Map<String, Object>>asList(
                Map.of("pidsLimit", -2), Map.of("cpuQuota", 500), Map.of("cpuPeriod", 1000001),
                Map.of("runtime", "runc; touch /tmp/unsafe"), Map.of("tmpfs", Map.of("relative", "size=64m")),
                Map.of("tmpfs", Map.of("/tmp/../etc", "")), Map.of("sysctls", Map.of("net.core.somaxconn", "")),
                Map.of("ulimits", List.of(Map.of("name", "nofile", "soft", 4096, "hard", 1024))),
                Map.of("ulimits", List.of(Map.of("name", "unsupported", "soft", -1, "hard", -1))),
                Map.of("ulimits", List.of(Map.of("name", "nofile", "soft", 1, "hard", 2), Map.of("name", "nofile", "soft", 1, "hard", 2))))) {
            assertNotNull(config.toString(), HardwareOptions.error(config));
        }
        assertNull(HardwareOptions.error(Map.of("pidsLimit", -1, "cpuQuota", -1, "cpuPeriod", 0,
                "tmpfs", Map.of("/run", ""), "sysctls", Map.of("net.core.somaxconn", "1024"),
                "ulimits", List.of(Map.of("name", "memlock", "soft", -1, "hard", -1)))));
    }
}
