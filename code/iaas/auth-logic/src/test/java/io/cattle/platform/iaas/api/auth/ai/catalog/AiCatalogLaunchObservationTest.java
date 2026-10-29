package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.core.model.tables.records.VolumeTemplateRecord;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.After;
import org.junit.Test;

public class AiCatalogLaunchObservationTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void activeStackDoesNotVerifyUntilExactCreatesLedgerLinksAndOwnerDeploymentAllConverge() {
        Fixture f = new Fixture(); f.check(false);
        f.plan.put("createdGraph", Map.of("service:app", 900L, "volumeTemplate:data", 901L)); f.check(false);
        f.plan.put("ledger", Map.of("create:service:app", done(), "links:app", done(), "activate:app", done(), "create:volumeTemplate:data", done()));
        f.check(false); f.plan.put("linksSettled", true); f.check(false);
        when(f.observation.deployments.isReady(f.service)).thenReturn(true); f.check(true);
        assertEquals(true, f.plan.get("delegationClosed")); verify(f.observation.store, atLeastOnce()).save(f.plan);
    }

    @Test public void unsettledLinksExtraChildrenAndReboundVolumeNeverBecomeVerified() {
        Fixture f = new Fixture(); f.plan.put("createdGraph", Map.of("service:app", 900L, "volumeTemplate:data", 901L));
        f.plan.put("ledger", Map.of("create:service:app", done(), "links:app", done(), "activate:app", done(), "create:volumeTemplate:data", done()));
        f.plan.put("linksSettled", true); when(f.observation.deployments.isReady(f.service)).thenReturn(true);
        ServiceConsumeMapRecord unexpected = new ServiceConsumeMapRecord(); unexpected.setAccountId(5L); unexpected.setState("active");
                unexpected.setConsumedServiceId(902L);
        when(f.objects.find(ServiceConsumeMap.class, "serviceId", 900L, "removed", null)).thenReturn(List.of(unexpected)); f.check(false);
        when(f.objects.find(ServiceConsumeMap.class, "serviceId", 900L, "removed", null)).thenReturn(List.of());
        when(f.objects.find(Service.class, "stackId", 889L, "removed", null)).thenReturn(List.of(f.service, new ServiceRecord())); f.check(false);
        when(f.objects.find(Service.class, "stackId", 889L, "removed", null)).thenReturn(List.of(f.service));
        f.volume.setAccountId(6L); f.check(false); f.volume.setAccountId(5L); f.volume.setName("rebound"); f.check(false);
        f.volume.setName("data"); f.check(true);
    }

    private static Map<String, Object> done() { return Map.of("state", "completed"); }
    private static final class Fixture {
        final AiCatalogLaunchObservation observation = new AiCatalogLaunchObservation();
        final ObjectManager objects = mock(ObjectManager.class); final ServiceRecord service = new ServiceRecord();
        final VolumeTemplateRecord volume = new VolumeTemplateRecord(); final Map<String, Object> plan = new LinkedHashMap<>();
        final ApiRequest request = new ApiRequest(null, mock(SchemaFactory.class));
        Fixture() {
            Policy actor = mock(Policy.class); SchemaFactory schemas = mock(SchemaFactory.class);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("stack", "st", "service", "s", "volumeTemplate", "vt"));
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(actor); ApiContext.getContext().setIdFormatter(formatter);
            StackRecord stack = new StackRecord(); stack.setId(889L); stack.setAccountId(5L); stack.setKind("stack"); stack.setState("active");
            service.setId(900L); service.setStackId(889L); service.setAccountId(5L); service.setName("app"); service.setState("active");
            volume.setId(901L); volume.setStackId(889L); volume.setAccountId(5L); volume.setName("data"); volume.setState("active");
            ResourceManager manager = mock(ResourceManager.class);
            when(manager.getById(eq("stack"), eq("1st889"), any())).thenReturn(stack);
            when(manager.getById(eq("service"), eq("1s900"), any())).thenReturn(service);
            when(manager.getById(eq("volumeTemplate"), eq("1vt901"), any())).thenReturn(volume);
            observation.access = new AiCatalogLaunchAccess() {
                @Override public Authorization bound(Map<String, Object> p, ApiRequest r) {
                    return new Authorization(new ApiAuthenticator.CurrentAuthorization(actor, schemas), null);
                }
                @Override public <T> T domain(ApiRequest original, Authorization a, String type, String method, String id, String action,
                        Map<String, Object> body, Function<ApiRequest, T> operation) {
                    ApiRequest domain = new ApiRequest(null, schemas); domain.setType(type); domain.setId(id); return operation.apply(domain);
                }
                @Override ResourceManager manager(String type) { return manager; }
            };
            observation.access.objects = objects; observation.store = mock(AiCatalogLaunchPlanStore.class);
            observation.deployments = mock(ServiceDeploymentReadiness.class);
            Map<String, Object> graph = Map.of("services", List.of(Map.of("name", "app", "links", Map.of("serviceLinks", List.of()))),
                    "volumes", List.of(Map.of("name", "data")));
            observation.privateData = new AiCatalogLaunchPrivate() { @Override public Map<String, Object> open(Map<String, Object> p) { return
                    Map.of("graph", graph); } };
            when(objects.find(Service.class, "stackId", 889L, "removed", null)).thenReturn(List.of(service));
            when(objects.find(ServiceConsumeMap.class, "serviceId", 900L, "removed", null)).thenReturn(List.of());
            plan.putAll(Map.of("id", "aicataloglaunch-fixture", "internalStackId", 889L, "accountId", 5L, "startOnCreate", true,
                    "dispatchAcknowledged", true, "acceptedAt", System.currentTimeMillis()));
        }
        void check(boolean expected) {
            plan.remove("verifiedResult"); observation.refresh(plan, request);
            assertEquals(expected, Boolean.TRUE.equals(plan.get("verifiedResult"))); assertEquals(expected ? "succeeded" : "running", plan.get("status"));
        }
    }
}
