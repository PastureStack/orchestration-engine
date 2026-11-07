package io.cattle.platform.iaas.api.volume;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.tables.records.VolumeRecord;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiTypedLocalVolumeNativeContractTest {
    @After public void removeContext() { ApiContext.remove(); }
    @Test public void builtinLocalUsesTheExistingNativeFilterAndOrdinaryPluginApiIsUnchanged() {
        var filter = new VolumeCreateValidationFilter(); filter.objectManager = mock(ObjectManager.class);
        Policy policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(77L); ApiContext.newContext().setPolicy(policy);
        var volume = new VolumeRecord(); var next = mock(ResourceManager.class);
        for (String driver : new String[] {"local", "ordinary-plugin"}) {
            var request = spy(new ApiRequest(null, null));
            request.setRequestObject(Map.of("type", "volume", "name", "data", "driver", driver, "driverOpts", Map.of()));
            doReturn(volume).when(request).proxyRequestObject(Volume.class);
            when(next.create("volume", request)).thenReturn(volume);
            assertSame(volume, filter.create("volume", request, next));
        }
    }
    @Test public void nativeMissingDriverAndUnsupportedSizeStillRejectWithoutDispatch() {
        var filter = new VolumeCreateValidationFilter(); filter.objectManager = mock(ObjectManager.class);
        Policy policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(77L); ApiContext.newContext().setPolicy(policy);
        var volume = new VolumeRecord(); var next = mock(ResourceManager.class);
        var request = spy(new ApiRequest(null, null)); request.setRequestObject(Map.of("name", "data"));
        doReturn(volume).when(request).proxyRequestObject(Volume.class);
        assertThrows(ClientVisibleException.class, () -> filter.create("volume", request, next));
        request.setRequestObject(Map.of("name", "data", "driver", "local")); volume.setSizeMb(1L);
        assertThrows(ClientVisibleException.class, () -> filter.create("volume", request, next)); verifyNoInteractions(next);
    }
}
