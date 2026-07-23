package io.github.ibuildthecloud.gdapi.request.resource.impl;

import static org.junit.Assert.assertEquals;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Collection;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.AbstractResourceManagerFilter;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerFilter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ResourceManagerLocatorImplTest {

    interface FilterTarget {
    }

    @Test
    public void classFilterFallsBackToDecapitalizedTypeNameWhenSchemaIsNotReady() {
        ResourceManagerLocatorImpl locator = new ResourceManagerLocatorImpl();
        locator.setSchemaFactory(new EmptySchemaFactory());
        locator.setResourceManagers(Collections.<ResourceManager>emptyList());
        locator.setResourceManagerFilters(Arrays.<ResourceManagerFilter>asList(new FilterTargetFilter()));
        locator.setDefaultResourceManager(new DefaultManager());
        locator.init();

        Object result = locator.getResourceManagerByType("filterTarget")
                .create("filterTarget", new ApiRequest(null, null));
        Object lowerCaseResult = locator.getResourceManagerByType("filtertarget")
                .create("filtertarget", new ApiRequest(null, null));

        assertEquals("filtered", result);
        assertEquals("filtered", lowerCaseResult);
    }

    @Test
    public void initClearsCachedResourceManagersBeforeRebuildingFilters() {
        ResourceManagerLocatorImpl locator = new ResourceManagerLocatorImpl();
        locator.setSchemaFactory(new EmptySchemaFactory());
        locator.setResourceManagers(Collections.<ResourceManager>emptyList());
        locator.setResourceManagerFilters(Collections.<ResourceManagerFilter>emptyList());
        locator.setDefaultResourceManager(new DefaultManager());
        locator.init();

        Object unfiltered = locator.getResourceManagerByType("filterTarget")
                .create("filterTarget", new ApiRequest(null, null));

        locator.setResourceManagerFilters(Arrays.<ResourceManagerFilter>asList(new FilterTargetFilter()));
        locator.init();

        Object filtered = locator.getResourceManagerByType("filterTarget")
                .create("filterTarget", new ApiRequest(null, null));

        assertEquals("default", unfiltered);
        assertEquals("filtered", filtered);
    }

    static class FilterTargetFilter extends AbstractResourceManagerFilter {
        @Override
        public Class<?>[] getTypeClasses() {
            return new Class<?>[] { FilterTarget.class };
        }

        @Override
        public Object create(String type, ApiRequest request, ResourceManager next) {
            return "filtered";
        }
    }

    static class DefaultManager implements ResourceManager {
        @Override
        public String[] getTypes() {
            return new String[0];
        }

        @Override
        public Class<?>[] getTypeClasses() {
            return new Class<?>[0];
        }

        @Override
        public Object getById(String type, String id, ListOptions options) {
            return null;
        }

        @Override
        public Object getLink(String type, String id, String link, ApiRequest request) {
            return null;
        }

        @Override
        public Object list(String type, ApiRequest request) {
            return null;
        }

        @Override
        public List<?> list(String type, Map<Object, Object> criteria, ListOptions options) {
            return Collections.emptyList();
        }

        @Override
        public Object create(String type, ApiRequest request) {
            return "default";
        }

        @Override
        public Object update(String type, String id, ApiRequest request) {
            return null;
        }

        @Override
        public Object delete(String type, String id, ApiRequest request) {
            return null;
        }

        @Override
        public Object resourceAction(String type, ApiRequest request) {
            return null;
        }

        @Override
        public Object collectionAction(String type, ApiRequest request) {
            return null;
        }

        @Override
        public Collection convertResponse(List<?> object, ApiRequest request) {
            return null;
        }

        @Override
        public Resource convertResponse(Object obj, ApiRequest request) {
            return null;
        }

        @Override
        public boolean handleException(Throwable t, ApiRequest request) {
            return false;
        }
    }

    static class EmptySchemaFactory implements SchemaFactory {
        @Override
        public String getId() {
            return "empty";
        }

        @Override
        public List<Schema> listSchemas() {
            return Collections.emptyList();
        }

        @Override
        public String getSchemaName(Class<?> clz) {
            return null;
        }

        @Override
        public String getSchemaName(String type) {
            return null;
        }

        @Override
        public Schema getSchema(Class<?> clz) {
            return null;
        }

        @Override
        public Schema getSchema(String type) {
            return null;
        }

        @Override
        public Class<?> getSchemaClass(String type, boolean resolveParent) {
            return null;
        }

        @Override
        public Class<?> getSchemaClass(String type) {
            return null;
        }

        @Override
        public Class<?> getSchemaClass(Class<?> type) {
            return null;
        }

        @Override
        public String getPluralName(String type) {
            return null;
        }

        @Override
        public String getSingularName(String type) {
            return null;
        }

        @Override
        public String getBaseType(String type) {
            return null;
        }

        @Override
        public Schema registerSchema(Object obj) {
            return null;
        }

        @Override
        public Schema parseSchema(String name) {
            return null;
        }

        @Override
        public List<String> getSchemaNames(Class<?> clz) {
            return Collections.emptyList();
        }

        @Override
        public boolean typeStringMatches(Class<?> clz, String type) {
            return false;
        }
    }
}
