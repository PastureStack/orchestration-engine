package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionAuthorization;
import io.cattle.platform.eventing.model.EventVO;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeySubscriptionAuthorizationTest {
    @Before public void setup(){ApiContext.newContext().setIdFormatter(new IdentityFormatter());}
    @After public void cleanup(){ApiContext.remove();}

    @Test public void handshakeAndEachPingUseLiveRevisionOwnerAndExpiry(){
        var f=new ApiKeyDelegationServiceTest.Fixture();
        var authorization=new ApiKeySubscriptionAuthorization();authorization.delegations=f.service;
        ApiRequest request=subscription(f);
        SubscriptionAuthorization.Session session=authorization.capture(request);
        EventVO<Object> ping=new EventVO<>();ping.setName("ping");
        assertSame(f.owner,session.currentPolicy(ping));
        f.key.setState("inactive");ApiKeyDelegationServiceTest.assertCode("ApiKeyRevoked",()->session.currentPolicy(ping));
        f.key.setState("active");f.store(f.policy,4);ApiKeyDelegationServiceTest.assertCode("ApiKeyPolicyChanged",()->session.currentPolicy(ping));
        f.store(f.policy,3);f.ownerAllowed=false;ApiKeyDelegationServiceTest.assertCode("OwnerPermissionDenied",()->session.currentPolicy(ping));
        f.ownerAllowed=true;f.service.clock=Clock.fixed(f.now.plusSeconds(90),ZoneOffset.UTC);
        ApiKeyDelegationServiceTest.assertCode("ApiKeyExpired",()->session.currentPolicy(ping));
        ApiKeyDelegationServiceTest.assertCode("ApiKeyExpired",()->authorization.capture(request));
    }

    @Test public void eventResourcesAreRecheckedAndOutOfScopeBodiesAreFiltered(){
        var f=new ApiKeyDelegationServiceTest.Fixture();f.store(ApiKeyDelegationServiceTest.Fixture.custom(Set.of("read")),3);
        var authorization=new ApiKeySubscriptionAuthorization();authorization.delegations=f.service;
        SubscriptionAuthorization.Session session=authorization.capture(subscription(f));
        EventVO<Object> event=new EventVO<>();event.setName("resource.change");event.setResourceType("container");event.setResourceId("9");
        assertSame(f.owner,session.currentPolicy(event));
        f.store(ApiKeyDelegationServiceTest.Fixture.custom(Set.of("exec")),3);
        assertNull(session.currentPolicy(event));
        event.setResourceId(null);assertNull(session.currentPolicy(event));
    }

    @Test public void legacyAndFullNoExpiryHaveNoNewSubscriptionRestriction(){
        var f=new ApiKeyDelegationServiceTest.Fixture();var authorization=new ApiKeySubscriptionAuthorization();authorization.delegations=f.service;
        ApiRequest request=subscription(f);
        ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(12,42,"apiKey",0,null));assertNull(authorization.capture(request));
        f.store(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,java.util.List.of()),3);
        ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(12,42,"apiKey",3,f.policy));assertNull(authorization.capture(request));
    }

    private ApiRequest subscription(ApiKeyDelegationServiceTest.Fixture f){
        ApiRequest request=f.request();request.setType("subscribe");request.setId(null);request.setAction(null);request.setMethod("GET");return request;
    }
}
