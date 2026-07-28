package io.cattle.platform.allocator.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.cattle.platform.allocator.constraint.Constraint;
import io.cattle.platform.allocator.constraint.ContainerAffinityConstraint;
import io.cattle.platform.allocator.constraint.ContainerLabelAffinityConstraint;
import io.cattle.platform.allocator.constraint.HostAffinityConstraint;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;

public class AllocationHelperImplAffinityTest {

    @Test
    public void extractsEnvAffinityConstraintsFromWildcardMap() {
        AllocationHelperImpl helper = new AllocationHelperImpl();
        Map<Object, Object> env = new LinkedHashMap<Object, Object>();
        env.put(ContainerAffinityConstraint.ENV_HEADER_AFFINITY_CONTAINER + "==web", "ignored");
        env.put(HostAffinityConstraint.ENV_HEADER_AFFINITY_HOST_LABEL + "rack==a", "ignored");
        env.put(ContainerLabelAffinityConstraint.ENV_HEADER_AFFINITY_CONTAINER_LABEL + "tier==api", "ignored");

        List<Constraint> constraints = helper.extractConstraintsFromEnv(env);

        assertEquals(3, constraints.size());
        assertTrue(constraints.get(0) instanceof ContainerAffinityConstraint);
        assertTrue(constraints.get(1) instanceof HostAffinityConstraint);
        assertTrue(constraints.get(2) instanceof ContainerLabelAffinityConstraint);
    }

    @Test
    public void extractsLabelAffinityConstraintsFromWildcardMap() {
        AllocationHelperImpl helper = new AllocationHelperImpl();
        Map<Object, Object> labels = new LinkedHashMap<Object, Object>();
        labels.put(ContainerLabelAffinityConstraint.LABEL_HEADER_AFFINITY_CONTAINER_LABEL, "tier=api");
        labels.put(ContainerAffinityConstraint.LABEL_HEADER_AFFINITY_CONTAINER, "web");
        labels.put(HostAffinityConstraint.LABEL_HEADER_AFFINITY_HOST_LABEL, "rack=a");

        List<Constraint> constraints = helper.extractConstraintsFromLabels(labels, null);

        assertEquals(3, constraints.size());
        assertTrue(constraints.get(0) instanceof ContainerLabelAffinityConstraint);
        assertTrue(constraints.get(1) instanceof ContainerAffinityConstraint);
        assertTrue(constraints.get(2) instanceof HostAffinityConstraint);
    }

    @Test
    public void extractsAllocationLocksFromEnvAndMergedSidekickLabels() {
        AllocationHelperImpl helper = new AllocationHelperImpl();
        helper.jsonMapper = new JacksonJsonMapper();

        InstanceRecord primary = new InstanceRecord();
        Map<Object, Object> env = new LinkedHashMap<Object, Object>();
        env.put(ContainerAffinityConstraint.ENV_HEADER_AFFINITY_CONTAINER + "==web", "ignored");
        DataAccessor.setField(primary, InstanceConstants.FIELD_ENVIRONMENT, env);

        Map<Object, Object> primaryLabels = new LinkedHashMap<Object, Object>();
        primaryLabels.put(ContainerLabelAffinityConstraint.LABEL_HEADER_AFFINITY_CONTAINER_LABEL, "tier=api");
        DataAccessor.setField(primary, InstanceConstants.FIELD_LABELS, primaryLabels);

        InstanceRecord sidekick = new InstanceRecord();
        Map<Object, Object> sidekickLabels = new LinkedHashMap<Object, Object>();
        sidekickLabels.put(ContainerAffinityConstraint.LABEL_HEADER_AFFINITY_CONTAINER, "worker");
        DataAccessor.setField(sidekick, InstanceConstants.FIELD_LABELS, sidekickLabels);

        List<LockDefinition> locks = helper.extractAllocationLockDefinitions(primary,
                Arrays.<Instance>asList(primary, sidekick));

        assertEquals(3, locks.size());
        assertEquals("ALLOCATE.CONSTRAINT.AFFINITY.web", locks.get(0).getLockId());
        assertEquals("ALLOCATE.CONSTRAINT.AFFINITY.api", locks.get(1).getLockId());
        assertEquals("ALLOCATE.CONSTRAINT.AFFINITY.worker", locks.get(2).getLockId());
    }

    @Test
    public void normalizeLabelsSkipsServiceLookupWhenNoServiceShortcutNeedsIt() {
        CountingObjectManager objectManager = new CountingObjectManager(Collections.<Service>emptyList());
        AllocationHelperImpl helper = new AllocationHelperImpl();
        helper.objectManager = objectManager.proxy();
        Map<String, String> systemLabels = systemLabels("app", "app/web", "io.rancher.service.primary.launch.config");
        Map<String, String> userLabels = new LinkedHashMap<String, String>();
        userLabels.put("custom.label", "value");

        helper.normalizeLabels(7L, systemLabels, userLabels);

        assertEquals(0, objectManager.findCalls);
        assertEquals("value", userLabels.get("custom.label"));
    }

    @Test
    public void normalizeLabelsLooksUpServiceNamesOnlyForServiceShortcutRewrite() {
        CountingObjectManager objectManager = new CountingObjectManager(Arrays.<Service>asList(service("web")));
        AllocationHelperImpl helper = new AllocationHelperImpl();
        helper.objectManager = objectManager.proxy();
        Map<String, String> systemLabels = systemLabels("app", "app/web", "io.rancher.service.primary.launch.config");
        Map<String, String> userLabels = new LinkedHashMap<String, String>();
        userLabels.put(ContainerLabelAffinityConstraint.LABEL_HEADER_AFFINITY_CONTAINER_LABEL, "io.rancher.stack_service.name=web");

        helper.normalizeLabels(7L, systemLabels, userLabels);

        assertEquals(1, objectManager.findCalls);
        assertEquals("io.rancher.stack_service.name=app/web",
                userLabels.get(ContainerLabelAffinityConstraint.LABEL_HEADER_AFFINITY_CONTAINER_LABEL));
    }

    private static Map<String, String> systemLabels(String stackName, String stackServiceName, String launchConfig) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("io.rancher.stack.name", stackName);
        labels.put("io.rancher.stack_service.name", stackServiceName);
        labels.put("io.rancher.service.launch.config", launchConfig);
        return labels;
    }

    private static Service service(String name) {
        ServiceRecord record = new ServiceRecord();
        record.setName(name);
        return record;
    }

    private static class CountingObjectManager implements InvocationHandler {
        private final List<? extends Service> services;
        private int findCalls;

        CountingObjectManager(List<? extends Service> services) {
            this.services = services;
        }

        ObjectManager proxy() {
            return ObjectManager.class.cast(Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[] { ObjectManager.class }, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("find".equals(method.getName())) {
                findCalls++;
                return services;
            }
            throw new UnsupportedOperationException(method.getName());
        }
    }
}
