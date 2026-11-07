package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.*;
import io.cattle.platform.core.addon.ServiceLink;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.servicediscovery.api.dao.ServiceConsumeMapDao;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ServiceNamedLinkPairOwnerTest {
    @Test public void replacementRemovesOnlyTheExactAliasWhileLegacyNullRemovalKeepsItsContract() {
        var owner = new ServiceDiscoveryApiServiceImpl();
        var maps = new PairMaps(); owner.consumeMapDao = proxy(ServiceConsumeMapDao.class, maps);
        var scheduled = new ArrayList<Scheduled>();
        owner.objectProcessManager = proxy(ObjectProcessManager.class, (object, method, arguments) -> {
            if (!"scheduleProcessInstance".equals(method.getName()) || arguments.length != 3)
                throw new AssertionError("Unexpected native process call: " + method.getName());
            scheduled.add(new Scheduled((String) arguments[0], arguments[1], arguments[2])); return null;
        });
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); owner.lockManager = locks;
        var source = new ServiceRecord(); source.setId(2L);
        var blue = new ServiceConsumeMapRecord(); blue.setId(7L); blue.setConsumedServiceId(9L); blue.setName("blue");
        var red = new ServiceConsumeMapRecord(); red.setId(8L); red.setConsumedServiceId(9L); red.setName("red");
        maps.current = List.of(blue, red);
        owner.removeServiceLinkExactPair(source, new ServiceLink(9L, "red", null));
        assertOnlyScheduled(scheduled, red);
        assertFalse(scheduled.stream().anyMatch(call -> call.resource() == blue));
        scheduled.clear();
        owner.removeServiceLink(source, new ServiceLink(9L, null, null));
        assertOnlyScheduled(scheduled, blue);
        var plain = new ServiceConsumeMapRecord(); plain.setId(10L); plain.setConsumedServiceId(9L);
        maps.current = List.of(red, plain);
        scheduled.clear();
        owner.removeServiceLinkExactPair(source, new ServiceLink(9L, "", null));
        assertOnlyScheduled(scheduled, plain);
        var selected = new ServiceLink(9L, null, null); owner.addServiceLinkExactPair(source, selected);
        assertEquals(1, maps.exactCreates.size());
        assertSame(source, maps.exactCreates.get(0).source());
        assertSame(selected, maps.exactCreates.get(0).link());
        assertEquals(0, maps.legacyCreates.size());
    }

    private record Scheduled(String process, Object resource, Object data) { }
    private record Created(Object source, Object link) { }
    private static void assertOnlyScheduled(List<Scheduled> calls, Object resource) {
        assertEquals(1, calls.size());
        assertEquals(ServiceConstants.PROCESS_SERVICE_CONSUME_MAP_REMOVE, calls.get(0).process());
        assertSame(resource, calls.get(0).resource()); assertNull(calls.get(0).data());
    }
    private static <T> T proxy(Class<T> type, InvocationHandler calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (object, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                case "equals" -> object == arguments[0];
                case "hashCode" -> System.identityHashCode(object);
                case "toString" -> "test-" + type.getSimpleName();
                default -> throw new AssertionError(method.getName());
                };
            }
            return calls.invoke(object, method, arguments);
        }));
    }
    private static class PairMaps implements InvocationHandler {
        List<ServiceConsumeMapRecord> current = List.of();
        final List<Created> exactCreates = new ArrayList<>(), legacyCreates = new ArrayList<>();
        @Override public Object invoke(Object object, java.lang.reflect.Method method, Object[] arguments) {
            return switch (method.getName()) {
            case "findConsumedMapsForMutation" -> {
                assertEquals(2L, arguments[0]); yield current;
            }
            case "createServiceLinkExactPair" -> {
                exactCreates.add(new Created(arguments[0], arguments[1])); yield null;
            }
            case "createServiceLink" -> {
                legacyCreates.add(new Created(arguments[0], arguments[1])); yield null;
            }
            default -> throw new AssertionError("Unexpected DAO call: " + method.getName());
            };
        }
    }
}
