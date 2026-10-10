package io.github.ibuildthecloud.gdapi.request.resource.impl;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.AbstractResourceManagerFilter;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FilteredResourceManagerCollectionCapabilityTest {
    private ResourceManager guarded(Object required) {
        return new ResourceManagerLocatorImplTest.DefaultManager() {
            @Override public boolean supportsScopedCollectionQuery(Object guard) { return guard == required; }
        };
    }
    private AbstractResourceManagerFilter transparent() { return new AbstractResourceManagerFilter() { }; }

    @Test public void unknownManagerAndWrappedSyntheticDefaultToFalse() {
        Object guard = new Object();
        var unknown = new ResourceManagerLocatorImplTest.DefaultManager();
        assertFalse(unknown.supportsScopedCollectionQuery(guard));
        assertFalse(new FilteredResourceManager(transparent(), unknown).supportsScopedCollectionQuery(guard));
    }
    @Test public void nestedTransparentWrappersForwardOnlyTheSameGuard() {
        Object guard = new Object();
        ResourceManager manager = new FilteredResourceManager(transparent(),
                new FilteredResourceManager(transparent(), guarded(guard)));
        assertTrue(manager.supportsScopedCollectionQuery(guard));
        assertFalse(manager.supportsScopedCollectionQuery(new Object()));
    }
    @Test public void requestListOverrideCannotAdvertiseTheDelegateCapability() {
        Object guard = new Object();
        var filter = new AbstractResourceManagerFilter() {
            @Override public Object list(String type, ApiRequest request, ResourceManager next) {
                return next.list(type, request);
            }
        };
        assertFalse(new FilteredResourceManager(filter, guarded(guard)).supportsScopedCollectionQuery(guard));
    }
    @Test public void criteriaListOverrideCannotAdvertiseTheDelegateCapability() {
        Object guard = new Object();
        var filter = new AbstractResourceManagerFilter() {
            @Override public List<?> list(String type, Map<Object, Object> criteria, ListOptions options, ResourceManager next) {
                return next.list(type, criteria, options);
            }
        };
        assertFalse(new FilteredResourceManager(filter, guarded(guard)).supportsScopedCollectionQuery(guard));
    }
    @Test public void wrapperListOverridesCannotAdvertiseInheritedCapability() {
        Object guard = new Object();
        var requestWrapper = new FilteredResourceManager(transparent(), guarded(guard)) {
            @Override public Object list(String type, ApiRequest request) { return List.of(); }
        };
        var criteriaWrapper = new FilteredResourceManager(transparent(), guarded(guard)) {
            @Override public List<?> list(String type, Map<Object, Object> criteria, ListOptions options) { return List.of(); }
        };
        assertFalse(requestWrapper.supportsScopedCollectionQuery(guard));
        assertFalse(criteriaWrapper.supportsScopedCollectionQuery(guard));
    }
    @Test public void incompleteChainsAndNullGuardFailClosed() {
        Object guard = new Object();
        assertFalse(new FilteredResourceManager(null, guarded(guard)).supportsScopedCollectionQuery(guard));
        assertFalse(new FilteredResourceManager(transparent(), null).supportsScopedCollectionQuery(guard));
        assertFalse(new FilteredResourceManager(transparent(), guarded(guard)).supportsScopedCollectionQuery(null));
    }
}
