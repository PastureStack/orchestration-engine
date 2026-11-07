package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.addon.ServiceLink;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.servicediscovery.api.dao.ServiceConsumeMapDao;
import java.util.List;
import org.junit.Test;

public class ServiceNamedLinkPairOwnerTest {
    @Test public void replacementRemovesOnlyTheExactAliasWhileLegacyNullRemovalKeepsItsContract() {
        var owner = new ServiceDiscoveryApiServiceImpl(); owner.consumeMapDao = mock(ServiceConsumeMapDao.class);
        owner.objectProcessManager = mock(ObjectProcessManager.class);
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); owner.lockManager = locks;
        var source = new ServiceRecord(); source.setId(2L);
        var blue = new ServiceConsumeMapRecord(); blue.setId(7L); blue.setConsumedServiceId(9L); blue.setName("blue");
        var red = new ServiceConsumeMapRecord(); red.setId(8L); red.setConsumedServiceId(9L); red.setName("red");
        doReturn(List.of(blue, red)).when(owner.consumeMapDao).findConsumedMapsForMutation(2L);
        owner.removeServiceLinkExactPair(source, new ServiceLink(9L, "red", null));
        verify(owner.objectProcessManager).scheduleProcessInstance(ServiceConstants.PROCESS_SERVICE_CONSUME_MAP_REMOVE, red, null);
        verify(owner.objectProcessManager, never()).scheduleProcessInstance(ServiceConstants.PROCESS_SERVICE_CONSUME_MAP_REMOVE, blue, null);
        reset(owner.objectProcessManager);
        owner.removeServiceLink(source, new ServiceLink(9L, null, null));
        verify(owner.objectProcessManager).scheduleProcessInstance(ServiceConstants.PROCESS_SERVICE_CONSUME_MAP_REMOVE, blue, null);
        var plain = new ServiceConsumeMapRecord(); plain.setId(10L); plain.setConsumedServiceId(9L);
        doReturn(List.of(red, plain)).when(owner.consumeMapDao).findConsumedMapsForMutation(2L);
        reset(owner.objectProcessManager);
        owner.removeServiceLinkExactPair(source, new ServiceLink(9L, "", null));
        verify(owner.objectProcessManager).scheduleProcessInstance(ServiceConstants.PROCESS_SERVICE_CONSUME_MAP_REMOVE, plain, null);
        var selected = new ServiceLink(9L, null, null); owner.addServiceLinkExactPair(source, selected);
        verify(owner.consumeMapDao).createServiceLinkExactPair(source, selected);
        verify(owner.consumeMapDao, never()).createServiceLink(source, selected);
    }
}
