package io.cattle.platform.iaas.api.filter.compat;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.cattle.platform.core.model.tables.records.StackRecord;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.HashMap;
import org.junit.Test;

public class CompatibilityOutputFilterTest {
    @Test public void modernV1KeepsAdvertisedCanonicalStackIdentityAndUrls() throws Exception {
        ApiRequest request = request("v1", false);
        ResourceImpl stack = resource("stack", "1st1");
        new CompatibilityOutputFilter().filter(request, null, stack);
        assertEquals("stack", stack.getType()); assertEquals("1st1", stack.getId());
        assertEquals("http://qa/v1/stacks/1st1", stack.getLinks().get("self").toString());
        assertEquals("http://qa/v1/stacks/1st1/?action=update", stack.getActions().get("update").toString());
    }
    @Test public void modernV1ServiceDoesNotInventAnEnvironmentRelation() throws Exception {
        ResourceImpl service = resource("service", "1s1");
        Resource result = new CompatibilityOutputFilter().filter(request("v1", false), null, service);
        assertSame(service, result); assertFalse(service.getFields().containsKey("environmentId"));
        assertEquals("http://qa/v1/stacks/1st1", service.getLinks().get("self").toString());
    }
    @Test public void realLegacyAliasStillConvertsAndV2NeverDoes() throws Exception {
        ApiContext.newContext();
        try {
            ResourceImpl legacy = resource("stack", "1st1");
            StackRecord original = new StackRecord(); original.setId(1L);
            new CompatibilityOutputFilter().filter(request("v1", true), original, legacy);
            assertEquals("environment", legacy.getType()); assertEquals("1", legacy.getId());
            assertEquals("http://qa/v1/environments/1st1", legacy.getLinks().get("self").toString());
            ResourceImpl modern = resource("stack", "1st1");
            new CompatibilityOutputFilter().filter(request("v2-beta", true), null, modern);
            assertEquals("stack", modern.getType()); assertEquals("1st1", modern.getId());
        } finally { ApiContext.remove(); }
    }
    private ApiRequest request(String version, boolean alias) {
        ApiRequest request = new ApiRequest(null, null); request.setVersion(version);
        SchemaImpl environment = new SchemaImpl(); environment.setId("environment");
        request.setSchemaFactory((SchemaFactory) Proxy.newProxyInstance(SchemaFactory.class.getClassLoader(), new Class<?>[]{SchemaFactory.class},
                (proxy, method, args) -> method.getName().equals("getSchema") && alias && "environment".equals(args[0]) ? environment : null));
        return request;
    }
    private ResourceImpl resource(String type, String id) throws Exception {
        ResourceImpl resource = new ResourceImpl(id, type, new HashMap<>());
        resource.setLinks(new HashMap<>(java.util.Map.of("self", new URL("http://qa/v1/stacks/1st1"))));
        resource.getActions().put("update", new URL("http://qa/v1/stacks/1st1/?action=update"));
        return resource;
    }
}
