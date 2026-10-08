package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.tables.records.AgentRecord;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyDelegationFailureTest {
    @Before public void setup(){ApiContext.newContext().setIdFormatter(new IdentityFormatter());}
    @After public void cleanup(){ApiContext.remove();}

    @Test public void dualProofRecordsOnlyFixedFailureWithoutInstallingAuthorization(){
        Fixture f=new Fixture();ApiRequest request=f.request();
        var result=f.service().recordProxyFailure(request);
        assertEquals(true,result.get("accepted"));assertNull(ApiContext.getContext().getPolicy());
        assertNull(ApiKeyCredentialContext.get(request));assertEquals(1,f.base.events.size());
        var event=f.base.events.getFirst();assertEquals("FAILED",event.outcome());
        assertEquals("BackendAuditCapabilityUnavailable",event.failureCode());
        assertEquals("container",event.targetType());assertEquals("1i9",event.targetId());
        assertFalse(event.toString().contains(f.backendToken));assertFalse(event.toString().contains(f.base.token));
    }

    @Test public void missingUnsignedOrWrongPurposeBackendCannotForgeReceipt(){
        Fixture f=new Fixture();ApiRequest request=f.request();
        request.setRequestObject(Map.of("token",f.base.token));invalid(f,request,"ApiKeyDelegationInvalid");
        request.setRequestObject(Map.of("token",f.base.token,"backendToken","unsigned"));invalid(f,request,"ApiKeyDelegationInvalid");
        f.backend().remove("purpose");invalid(f,f.request(),"DelegatedAuditAgentRequired");
        f.backend().put("purpose","user-login");invalid(f,f.request(),"DelegatedAuditAgentRequired");
        assertTrue(f.base.events.isEmpty());
    }

    @Test public void legacyFullKeyTraceHasTheSameFailClosedAndEvidenceContract(){
        Fixture f=new Fixture();ApiContext.getContext().setPolicy(f.base.engine.owner);
        ApiRequest original=f.base.engine.request();
        ApiKeyCredentialContext.attach(original,new ApiKeyCredentialContext(12,42,"apiKey",0,null));
        original.setAttribute("apiKey.audit.decision","ALLOW");
        String legacy=f.service().token(original,f.base.engine.payload(),null);
        assertTrue(f.base.engine.tokens.get(legacy).containsKey(ApiKeyDelegationService.AUDIT_CLAIM));
        assertFalse(f.base.engine.tokens.get(legacy).containsKey(ApiKeyDelegationService.CLAIM));
        ApiContext.getContext().setPolicy(null);ApiRequest report=f.request();report.setRequestObject(Map.of("token",legacy,"backendToken",f.backendToken));
        assertEquals(true,f.service().recordProxyFailure(report).get("accepted"));
        assertEquals(0,f.base.events.getFirst().policyRevision());assertNull(ApiContext.getContext().getPolicy());
    }

    @Test public void backendHostMustMatchTheOriginalSignedTicket(){
        Fixture f=new Fixture();f.backend().put("reportedUuid","other-host");
        invalid(f,f.request(),"DelegatedAuditHostMismatch");assertTrue(f.base.events.isEmpty());
    }

    @Test public void removedInactiveOrUnknownAgentCannotReport(){
        Fixture f=new Fixture();f.agent.setState("inactive");invalid(f,f.request(),"DelegatedAuditAgentRequired");
        f.agent.setState("active");f.agent.setRemoved(new java.util.Date());invalid(f,f.request(),"DelegatedAuditAgentRequired");
        f.agent.setRemoved(null);f.backend().put("agentId",999L);invalid(f,f.request(),"DelegatedAuditAgentRequired");
        assertTrue(f.base.events.isEmpty());
    }

    @Test public void callerCannotChangeIdentityOutcomeOrFailureCode(){
        Fixture f=new Fixture();for(String field:List.of("accountId","agentId","outcome","failureCode","targetId")){
            var body=new HashMap<String,Object>(Map.of("token",f.base.token,"backendToken",f.backendToken));
            body.put(field,"SUCCEEDED");ApiRequest request=f.request();request.setRequestObject(body);invalid(f,request,"ApiKeyDelegationInvalid");
        }
        assertTrue(f.base.events.isEmpty());
    }

    @Test public void failureDedupeDoesNotSuppressLaterHostCompletion(){
        Fixture f=new Fixture();String first=(String)f.service().recordProxyFailure(f.request()).get("eventId");
        assertEquals(first,f.service().recordProxyFailure(f.request()).get("eventId"));
        ApiContext.getContext().setPolicy(ApiKeyDelegationServiceTest.proxy(Policy.class,(method,args)->
                method.equals("getOption") && Policy.AGENT_ID.equals(args[0])?"88":null));
        String terminal=(String)f.service().recordCompletion(f.base.request("SUCCEEDED",null)).get("eventId");
        assertNotEquals(first,terminal);assertEquals(f.base.events.getFirst().requestId(),f.base.events.getLast().requestId());
    }

    @Test public void expiredRevokedTicketCanRecordEvidenceButStaleBackendCannot(){
        Fixture f=new Fixture();f.base.engine.key.setState("inactive");
        f.service().clock=Clock.fixed(f.base.engine.now.plusSeconds(100),ZoneOffset.UTC);
        assertEquals(true,f.service().recordProxyFailure(f.request()).get("accepted"));
        f.service().clock=Clock.fixed(f.base.engine.now.plusSeconds(3601),ZoneOffset.UTC);
        f.service().auditRetentionSeconds=()->3600L;invalid(f,f.request(),"ApiKeyDelegationInvalid");
        f.service().clock=Clock.fixed(f.base.engine.now,ZoneOffset.UTC);
        f.backend().put("issuedAt",f.base.engine.now.plusSeconds(31).getEpochSecond());invalid(f,f.request(),"ApiKeyDelegationInvalid");
    }

    @Test public void durableAuditFailureDoesNotReturnAnAcceptedReceipt(){
        Fixture f=new Fixture();f.service().auditSinks=List.of();invalid(f,f.request(),"AuditUnavailable");
        assertTrue(f.base.events.isEmpty());
    }

    @Test public void handlerCommitsMinimalHttp200NoStoreAndRejectsOtherMethods() throws Exception {
        Fixture f=new Fixture();ByteArrayOutputStream output=new ByteArrayOutputStream();Map<String,String> headers=new HashMap<>();
        ServletOutputStream stream=new ServletOutputStream(){public void write(int b){output.write(b);}public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}};
        HttpServletResponse response=ApiKeyDelegationServiceTest.proxy(HttpServletResponse.class,(method,args)->{
            if(method.equals("setHeader"))headers.put((String)args[0],(String)args[1]);return method.equals("getOutputStream")?stream:null;});
        var servlet=ApiKeyDelegationServiceTest.proxy(HttpServletRequest.class,(method,args)->null);
        ApiRequest request=new ApiRequest(new ApiServletContext(servlet,response,null),null);
        request.setType("apiKeyDelegationFailure");request.setId("report");request.setMethod("POST");request.setRequestObject(f.request().getRequestObject());
        assertTrue(f.service().handleProxyFailure(request));assertTrue(request.isCommitted());assertEquals(200,request.getResponseCode());
        assertEquals("no-store",headers.get("Cache-Control"));assertNull(ApiContext.getContext().getPolicy());assertNull(ApiKeyCredentialContext.get(request));
        Map<String,Object> body=f.service().jsonMapper.readValue(output.toByteArray());assertEquals(java.util.Set.of("accepted","eventId"),body.keySet());
        assertFalse(output.toString().contains(f.backendToken));assertFalse(output.toString().contains(f.base.token));
        request.setMethod("GET");ApiKeyDelegationServiceTest.assertCode("ApiKeyDelegationInvalid",()->f.service().handleProxyFailure(request));
        request.setType("token");assertFalse(f.service().handleProxyFailure(request));
    }

    static void invalid(Fixture f,ApiRequest request,String code){ApiKeyDelegationServiceTest.assertCode(code,()->f.service().recordProxyFailure(request));}
    static final class Fixture {
        final ApiKeyDelegationCompletionTest.Fixture base=new ApiKeyDelegationCompletionTest.Fixture();
        final AgentRecord agent=new AgentRecord();final String backendToken;
        Fixture(){
            agent.setId(88L);agent.setState("active");
            service().objectManager=ApiKeyDelegationServiceTest.proxy(ObjectManager.class,(method,args)->
                    method.equals("loadResource") && args[0]==Agent.class?(Long.valueOf(88).equals(args[1])?agent:null):null);
            backendToken=service().tokenService.generateToken(Map.of("purpose","host-api-backend-v1","agentId",88L,
                    "reportedUuid","h1","issuedAt",base.engine.now.getEpochSecond()));
            ApiContext.getContext().setPolicy(null);
        }
        ApiKeyDelegationService service(){return base.engine.service;}
        Map<String,Object> backend(){return base.engine.tokens.get(backendToken);}
        ApiRequest request(){ApiRequest r=new ApiRequest(null,null);r.setRequestObject(Map.of("token",base.token,"backendToken",backendToken));return r;}
    }
}
