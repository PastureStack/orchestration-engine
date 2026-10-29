package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.upgrade.AiServiceUpgradePlanStore;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.*;
import org.junit.*;

public class AiSyntheticKeyAuthorizationTest {
    Policy owner;
    ApiRequest request;
    ApiKeyTargetResolver targets;
    ServiceRecord service;
    Schema schema;
    String planId = "aiplan-11111111-1111-1111-1111-111111111111";
    @Before public void setup() {
        owner=mock(Policy.class); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L); when(owner.getAccountId()).thenReturn(77L);
        ApiContext.newContext().setPolicy(owner);
        SchemaFactory factory=mock(SchemaFactory.class); schema=mock(Schema.class); when(factory.getSchema(anyString())).thenReturn(schema);
        when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
        request=new ApiRequest(null,factory); request.setType("aiServiceUpgradePlan"); request.setMethod("POST");
        targets=spy(new ApiKeyTargetResolver()); targets.objectManager=mock(ObjectManager.class); targets.aiPlans=mock(AiServiceUpgradePlanStore.class);
        targets.idFormatter=new IdentityFormatter();
        service=new ServiceRecord(); service.setId(10L); service.setAccountId(77L);
        when(targets.objectManager.loadResource(eq(Service.class), any(Long.class))).thenReturn(service);
        doReturn(ApiKeyPolicyEvaluator.Target.stackResource("service","1s10","1a77","1st3")).when(targets).resolveObject("service",service,owner);
        key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,ApiKeyPolicy.Effect.ALLOW,null,List.of()));
    }
    @After public void cleanup() { ApiContext.remove(); }
    void key(ApiKeyPolicy policy) { ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(7,42,"apiKeyRestricted",3,policy)); }
    @Test public void mapsOnlyReviewedSyntheticOperations() {
        assertEquals("upgrade",ApiKeyOperations.of(request).id());
        request.setAction("apply"); assertEquals("upgrade",ApiKeyOperations.of(request).id());
        request.setAction("confirm"); assertFalse(ApiKeyOperations.of(request).registered());
        request.setAction(null); request.setType("aiServiceDescriptionUpdate"); assertEquals("update",ApiKeyOperations.of(request).id());
        request.setType("pluginPlan"); request.setAction("apply"); assertFalse(ApiKeyOperations.of(request).registered());
    }
    @Test public void previewAndDescriptionLoadRealServiceAndBoundPlanRejectsOtherKey() {
        request.setRequestObject(Map.of("serviceId",10L));
        assertEquals("service",targets.resolve(request,owner).getFirst().resourceType());
        request.setType("aiServiceDescriptionUpdate"); assertEquals("1s10",targets.resolve(request,owner).getFirst().resourceId());
        request.setType("aiServiceUpgradePlan"); request.setId(planId); request.setMethod("GET");
        Map<String,Object> plan=new HashMap<>(Map.of("internalServiceId",10L,"actorId",42L,"accountId",77L,"credentialId",7L,"credentialRevision",3L));
        when(targets.aiPlans.load(planId)).thenReturn(plan);
        assertEquals("1s10",targets.resolve(request,owner).getFirst().resourceId());
        plan.put("credentialId",8L);
        assertEquals("KeyScopeDenied",assertThrows(ClientVisibleException.class,()->targets.resolve(request,owner)).getCode());
        verify(targets.objectManager,never()).loadResource(eq("aiServiceUpgradePlan"),any(Long.class));
    }
    @Test public void storedNumericServiceIdentityWorksWithTheProductionFormatterForReadAndApply() {
        DefaultIdFormatter formatter = new DefaultIdFormatter();
        formatter.setSchemaFactory(request.getSchemaFactory());
        targets.idFormatter = formatter;
        assertNull(formatter.parseId("10")); // Persisted IDs are not public wire IDs.
        assertEquals("10", formatter.parseId("1s10"));
        request.setId(planId);
        Map<String,Object> plan = new HashMap<>(Map.of("internalServiceId",10L,"serviceId","1s10", "actorId",42L,
                "accountId",77L,"credentialId",7L,"credentialRevision",3L));
        when(targets.aiPlans.load(planId)).thenReturn(plan);
        request.setMethod("GET");
        assertEquals("1s10", targets.resolve(request,owner).getFirst().resourceId());
        request.setMethod("POST"); request.setAction("apply");
        assertEquals("1s10", targets.resolve(request,owner).getFirst().resourceId());
        for (String field : List.of("actorId", "accountId", "credentialId", "credentialRevision")) {
            Object original = plan.put(field, 999L);
            assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> targets.resolve(request,owner)).getCode());
            plan.put(field, original);
        }
        for (Object invalid : List.of(0L, -1L, 1.5d, "10")) {
            plan.put("internalServiceId", invalid);
            assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> targets.resolve(request,owner)).getCode());
        }
    }
    @Test public void fullKeyStillCannotConfirmAndAuditDoesNotNeedBusinessCreateScope() {
        ApiKeyAuthorizationService authorization=new ApiKeyAuthorizationService(); authorization.auditSinks=List.of(mock(ApiKeyAuditSink.class));
        key(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of())); request.setId(planId); request.setAction("confirm");
        assertEquals("AiKeyConfirmationForbidden",assertThrows(ClientVisibleException.class,()->authorization.authorize(request,owner)).getCode());
        request.setType("aiToolCallEvent"); request.setId(null); request.setAction(null);
        key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,ApiKeyPolicy.Effect.DENY,null,List.of()));
        authorization.authorize(request,owner); assertEquals("ALLOW",request.getAttribute("apiKey.audit.decision"));
        key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED,ApiKeyPolicy.Effect.DENY,null,List.of()));
        assertEquals("KeyPolicyDenied",assertThrows(ClientVisibleException.class,()->authorization.authorize(request,owner)).getCode());
    }
}
