package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiKeyDelegatedAuditEvent;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.dao.AgentDao;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyDelegationCompletionTest {
    @Before public void setup(){ApiContext.newContext().setIdFormatter(new IdentityFormatter());}
    @After public void cleanup(){ApiContext.remove();}

    @Test public void expiredAndRevokedTicketMayOnlyRecordAgentBoundEvidence(){
        Fixture f=new Fixture();f.engine.key.setState("inactive");
        f.engine.service.clock=Clock.fixed(f.engine.now.plusSeconds(100),ZoneOffset.UTC);
        ApiKeyDelegationServiceTest.assertCode("ApiKeyExpired",()->f.engine.service.introspect(f.token));
        Map<String,Object> result=f.engine.service.recordCompletion(f.request("SUCCEEDED",null));
        assertEquals(true,result.get("accepted"));assertEquals(1,f.events.size());
        ApiKeyDelegatedAuditEvent event=f.events.getFirst();
        assertEquals("12",event.keyId());assertEquals(42,event.principalAccountId());assertEquals(7,event.accountId());
        assertEquals("container",event.targetType());assertEquals("1i9",event.targetId());assertEquals("exec",event.operation());
        assertFalse(event.toString().contains(f.token));assertFalse(event.toString().contains("original-key-secret"));
    }

    @Test public void grantPossessionWithoutAnAuthenticatedAgentCannotForgeSuccess(){
        Fixture f=new Fixture();ApiContext.getContext().setPolicy(f.engine.owner);
        ApiKeyDelegationServiceTest.assertCode("DelegatedAuditAgentRequired",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        ApiContext.getContext().setPolicy(null);
        ApiKeyDelegationServiceTest.assertCode("DelegatedAuditAgentRequired",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        assertTrue(f.events.isEmpty());
    }

    @Test public void anotherHostOrUnsignedTicketCannotForgeTerminalOutcome(){
        Fixture f=new Fixture();f.engine.service.agentDao=ApiKeyDelegationServiceTest.proxy(AgentDao.class,(method,args)->Map.of());
        ApiKeyDelegationServiceTest.assertCode("DelegatedAuditHostMismatch",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        assertTrue(f.events.isEmpty());
    }

    @Test public void resultEnumFailureCodesAndServerDerivedIdentityAreControlled(){
        Fixture f=new Fixture();
        ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(f.request("SUCCEEDED","DockerFailure")));
        ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(f.request("FAILED","raw terminal message or secret")));
        ApiRequest injection=f.request("FAILED","DockerFailure");
        ((Map<String,Object>)injection.getRequestObject()).put("targetId","foreign-target");
        ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(injection));
        assertTrue(f.events.isEmpty());
    }

    @Test public void aTicketHasOneStableTerminalEventIdRegardlessOfRetryCandidate(){
        Fixture f=new Fixture();
        String first=(String)f.engine.service.recordCompletion(f.request("FAILED","DockerFailure")).get("eventId");
        String second=(String)f.engine.service.recordCompletion(f.request("SUCCEEDED",null)).get("eventId");
        assertEquals(first,second);assertEquals(64,first.length());
        assertEquals(f.events.get(0).requestId(),f.events.get(1).requestId());
        // Actual first-wins persistence is tested by the audit DAO/outbox owner.
    }

    @Test public void staleOrFutureAuditEvidenceIsRejectedWithoutAuthorization(){
        Fixture f=new Fixture();
        f.engine.service.clock=Clock.fixed(f.engine.now.plusSeconds(7*24*3600+1),ZoneOffset.UTC);
        ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        f.engine.service.clock=Clock.fixed(f.engine.now.minusSeconds(31),ZoneOffset.UTC);
        ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        assertTrue(f.events.isEmpty());
    }

    @Test public void effectiveAuditRetentionCapsReceiptAgeAndNonpositiveValuesFailClosed(){
        Fixture f=new Fixture();f.engine.service.auditRetentionSeconds=()->3600L;
        f.engine.service.clock=Clock.fixed(f.engine.now.plusSeconds(3601),ZoneOffset.UTC);
        ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        f.engine.service.clock=Clock.fixed(f.engine.now,ZoneOffset.UTC);
        for(long retention: new long[]{0,-1}){
            f.engine.service.auditRetentionSeconds=()->retention;
            ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.engine.service.recordCompletion(f.request("SUCCEEDED",null)));
        }
        assertTrue(f.events.isEmpty());
        f.engine.service.auditRetentionSeconds=()->3600L;
        assertEquals(true,f.engine.service.recordCompletion(f.request("SUCCEEDED",null)).get("accepted"));
    }

    @Test public void auditUnavailableIsAnOperationalFailureNotAnAuthorizationDenial(){
        Fixture f=new Fixture();f.engine.service.recordCompletion(f.request("FAILED","AuditUnavailable"));
        assertEquals("FAILED",f.events.getFirst().outcome());assertEquals("AuditUnavailable",f.events.getFirst().failureCode());
    }

    static final class Fixture {
        final ApiKeyDelegationServiceTest.Fixture engine=new ApiKeyDelegationServiceTest.Fixture();
        final String token=engine.issue(null);
        final List<ApiKeyDelegatedAuditEvent> events=new ArrayList<>();
        Fixture(){
            engine.service.auditRetentionSeconds=()->2592000L;
            engine.service.agentDao=ApiKeyDelegationServiceTest.proxy(AgentDao.class,(method,args)->method.equals("getHosts")?Map.of("h1",engine.host):null);
            engine.service.auditSinks=List.of(new ApiKeyAuditSink(){
                public void recordDecision(ApiRequest request,Policy policy){}
                public void recordDelegatedOutcome(ApiKeyDelegatedAuditEvent event){events.add(event);}
            });
            ApiContext.getContext().setPolicy(ApiKeyDelegationServiceTest.proxy(Policy.class,(method,args)->method.equals("getOption") && Policy.AGENT_ID.equals(args[0])?"88":null));
        }
        ApiRequest request(String outcome,String code){
            ApiRequest request=new ApiRequest(null,null);Map<String,Object> body=new HashMap<>(Map.of("token",token,"outcome",outcome));
            if(code!=null)body.put("failureCode",code);request.setRequestObject(body);return request;
        }
    }
}
