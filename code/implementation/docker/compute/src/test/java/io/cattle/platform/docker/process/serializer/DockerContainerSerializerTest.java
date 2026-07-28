package io.cattle.platform.docker.process.serializer;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.condition.Condition;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class DockerContainerSerializerTest {

    @Test
    public void volumesFromIdsAreCopiedToInCondition() {
        InstanceRecord instance = new InstanceRecord();
        instance.setKind(InstanceConstants.KIND_CONTAINER);
        instance.setData(new HashMap<String, Object>());
        DataAccessor.setField(instance, DockerInstanceConstants.FIELD_VOLUMES_FROM, Arrays.asList(101L, 102L));

        InstanceRecord container = new InstanceRecord();
        List<Instance> containers = Collections.<Instance>singletonList(container);
        CapturingObjectManager objectManager = new CapturingObjectManager(containers);

        DockerContainerSerializer serializer = new DockerContainerSerializer();
        serializer.setJsonMapper(new JacksonJsonMapper());
        serializer.setObjectManager(objectManager.proxy());

        Map<String, Object> data = new HashMap<String, Object>();
        serializer.process(instance, InstanceConstants.TYPE, data);

        assertEquals(Arrays.asList(101L, 102L), objectManager.instanceCondition.getValues());
        assertEquals(containers, data.get(DockerInstanceConstants.EVENT_FIELD_VOLUMES_FROM));
        assertEquals(Collections.emptyList(), data.get(DockerInstanceConstants.EVENT_FIELD_VOLUMES_FROM_DVM));
    }

    private static final class CapturingObjectManager implements InvocationHandler {
        private final List<Instance> containers;
        private Condition instanceCondition;

        CapturingObjectManager(List<Instance> containers) {
            this.containers = containers;
        }

        ObjectManager proxy() {
            return ObjectManager.class.cast(Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[] { ObjectManager.class }, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("find".equals(method.getName())) {
                Object[] values = (Object[]) args[2];
                Condition condition = (Condition) values[0];
                Class<?> type = (Class<?>) args[0];
                if (Instance.class.equals(type)) {
                    instanceCondition = condition;
                    return containers;
                }
                if (Volume.class.equals(type)) {
                    return Collections.emptyList();
                }
            }
            throw new UnsupportedOperationException(method.getName());
        }
    }
}
