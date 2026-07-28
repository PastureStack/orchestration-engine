package io.cattle.platform.allocator.constraint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.allocator.service.AllocationAttempt;
import io.cattle.platform.allocator.service.AllocationLog;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class VolumesFromConstraintProviderTest {

    @Test
    public void convertsIntegerVolumesFromEntriesToLongCollocationIds() {
        CapturingVolumesFromConstraintProvider provider = new CapturingVolumesFromConstraintProvider();
        provider.jsonMapper = new JacksonJsonMapper();
        Set<Integer> volumesFrom = new LinkedHashSet<Integer>(Arrays.asList(Integer.valueOf(2), Integer.valueOf(3)));
        InstanceRecord instance = instanceWithVolumesFrom(1L, volumesFrom);
        List<Constraint> constraints = new ArrayList<Constraint>();

        provider.appendConstraints(newAttempt(instance), new AllocationLog(), constraints);

        assertEquals(new LinkedHashSet<Long>(Arrays.asList(Long.valueOf(2), Long.valueOf(3))), provider.collocatedInstances);
        assertTrue(constraints.isEmpty());
    }

    @Test(expected = ClassCastException.class)
    public void keepsLegacyIntegerCastBoundaryForVolumesFromEntries() {
        CapturingVolumesFromConstraintProvider provider = new CapturingVolumesFromConstraintProvider();
        provider.jsonMapper = new JacksonJsonMapper();
        InstanceRecord instance = instanceWithVolumesFrom(1L,
                new LinkedHashSet<Long>(Collections.singletonList(Long.valueOf(2))));

        provider.appendConstraints(newAttempt(instance), new AllocationLog(), new ArrayList<Constraint>());
    }

    private static InstanceRecord instanceWithVolumesFrom(Long id, Set<?> volumesFrom) {
        InstanceRecord instance = new InstanceRecord();
        instance.setId(id);
        DataAccessor.setField(instance, DockerInstanceConstants.FIELD_VOLUMES_FROM, volumesFrom);
        return instance;
    }

    private static AllocationAttempt newAttempt(Instance instance) {
        return new AllocationAttempt(1L, Collections.singletonList(instance), null, null,
                Collections.<Volume>emptySet(), Collections.<Volume, Set<StoragePool>>emptyMap());
    }

    private static final class CapturingVolumesFromConstraintProvider extends VolumesFromConstraintProvider {
        Set<Long> collocatedInstances = Collections.emptySet();

        @Override
        public Map<Long, Set<Long>> checkAndGetCollocatedInstanceHosts(Set<Long> collocatedInstances,
                Collection<Instance> coscheduledInstances) {
            this.collocatedInstances = new LinkedHashSet<Long>(collocatedInstances);
            return Collections.emptyMap();
        }
    }
}
