package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.*;
import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.addon.ServiceUpgrade;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.storage.service.StorageService;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AiServiceUpgradeValidationTest {
    @Test public void existingUpgradePathRetainsPrimaryAndSecondaryAndOnlyServerPlanChainsFinish() throws Exception {
        for (boolean planned : new boolean[] { false, true }) {
            AtomicInteger ordinaryWrites = new AtomicInteger(); AtomicInteger guardedWrites = new AtomicInteger();
            JacksonJsonMapper mapper = new JacksonJsonMapper();
            ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter() {
                @Override protected void assertUpgradePortsAvailable(Service s, InServiceUpgradeStrategy strategy) { }
                @Override protected void assertUpgradeVolumesAvailable(Service s, InServiceUpgradeStrategy strategy) { }
            };
            filter.jsonMapper = mapper;
            filter.objectManager = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(), new Class<?>[] { ObjectManager.class },
                    (proxy, method, args) -> { if (method.getName().equals("persist")) ordinaryWrites.incrementAndGet(); return null; });
            filter.storageService = (StorageService) Proxy.newProxyInstance(StorageService.class.getClassLoader(), new Class<?>[] { StorageService.class },
                    (proxy, method, args) -> method.getName().equals("isValidUUID") ? true : null);
            filter.aiUpgradePersistence = new AiServiceUpgradePersistence() {
                @Override public void persist(Service target, Map<?, ?> snapshot) { guardedWrites.incrementAndGet(); }
            };
            ServiceRecord service = new ServiceRecord(); service.setName("web"); service.setKind("service"); service.setData(new HashMap<>());
            Map<String, Object> primary = new LinkedHashMap<>(Map.of("imageUuid", "docker:web:1", "version", "old", "environment", Map.of("KEEP", "yes"),
                    "ports", List.of("8080:80/tcp"), "dataVolumes", List.of("named:/data"), "memory", 512));
            Map<String, Object> secondary = new LinkedHashMap<>(Map.of("name", "side", "imageUuid", "docker:side:1", "version", "side-old", "environment", Map.of("SIDE", "keep")));
            DataAccessor.setField(service, "launchConfig", primary); DataAccessor.setField(service, "secondaryLaunchConfigs", List.of(secondary));
            Map<String, Object> replacement = mapper.readValue(mapper.writeValueAsString(primary)); replacement.put("imageUuid", "docker:web:2");
            InServiceUpgradeStrategy strategy = new InServiceUpgradeStrategy(); strategy.setLaunchConfig(replacement);
            ServiceUpgrade upgrade = new ServiceUpgrade(); upgrade.setInServiceStrategy(strategy);
            ApiRequest request = new ApiRequest(null, null);
            if (planned) {
                request.setAttribute("ai.service.upgrade.plan", "aiplan-11111111-1111-1111-1111-111111111111");
                request.setAttribute("ai.service.upgrade.snapshot", Map.of("data", Map.of()));
            }
            filter.processInServiceUpgradeStrategy(request, service, upgrade, strategy);
            Map<String, Object> actual = new LinkedHashMap<>(DataAccessor.fieldMap(service, "launchConfig"));
            assertEquals("docker:web:2", actual.remove("imageUuid")); actual.remove("version");
            Map<String, Object> expected = new LinkedHashMap<>(primary); expected.remove("imageUuid"); expected.remove("version");
            assertEquals(expected, actual);
            assertEquals(List.of(secondary), DataAccessor.field(service, "secondaryLaunchConfigs", Object.class));
            Map<?, ?> payload = (Map<?, ?>) request.getRequestObject();
            assertEquals(planned ? "service.finishupgrade" : null, payload.get("service.upgrade::chain"));
            assertEquals(planned ? 1 : 0, guardedWrites.get());
            assertEquals(planned ? 0 : 1, ordinaryWrites.get());
        }
    }
}
