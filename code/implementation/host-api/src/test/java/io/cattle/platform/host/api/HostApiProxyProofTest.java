package io.cattle.platform.host.api;

import static org.junit.Assert.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.exception.ValidationErrorException;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HostApiProxyProofTest {
    @Before public void setup(){ApiContext.newContext();}
    @After public void cleanup(){ApiContext.remove();}
    @Test public void proofUsesVerifiedAgentAndPreservesOldReportedUuidContract(){
        Fixture f=new Fixture();long before=java.time.Instant.now().getEpochSecond();
        assertEquals("signed",f.manager.getToken("reported-1"));
        assertEquals("reported-1",f.claims.get("reportedUuid"));assertEquals(88L,f.claims.get("agentId"));
        assertEquals("host-api-backend-v1",f.claims.get("purpose"));assertTrue(((Number)f.claims.get("issuedAt")).longValue()>=before);
        assertEquals(java.util.Set.of("reportedUuid","agentId","issuedAt","purpose"),f.claims.keySet());
    }
    @Test public void callerWithoutAgentPolicyCannotIssueProof(){
        Fixture f=new Fixture();ApiContext.getContext().setPolicy(null);
        assertEquals("CantVerifyAgent",assertThrows(ClientVisibleException.class,()->f.manager.getToken("reported-1")).getCode());assertNull(f.claims);
    }
    @Test public void unknownAgentCannotIssueProof(){
        Fixture f=new Fixture();f.manager.objectManager=proxy(ObjectManager.class,(method,args)->null);
        assertThrows(ClientVisibleException.class,()->f.manager.getToken("reported-1"));assertNull(f.claims);
    }
    @Test public void anotherAgentsHostCannotIssueProof(){
        Fixture f=new Fixture();assertThrows(ValidationErrorException.class,()->f.manager.getToken("other-host"));assertNull(f.claims);
    }
    interface Call {Object call(String method,Object[] args);}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
            (instance,method,args)->call.call(method.getName(),args));}
    static final class Fixture {
        final HostApiProxyTokenManager manager=new HostApiProxyTokenManager();Map<String,Object> claims;
        Fixture(){
            Agent agent=proxy(Agent.class,(method,args)->method.equals("getId")?88L:null);
            Host host=proxy(Host.class,(method,args)->method.equals("getId")?5L:null);
            ApiContext.getContext().setPolicy(proxy(Policy.class,(method,args)->method.equals("getOption") && Policy.AGENT_ID.equals(args[0])?"88":null));
            manager.objectManager=proxy(ObjectManager.class,(method,args)->method.equals("loadResource") && args[0]==Agent.class && "88".equals(args[1])?agent:null);
            manager.agentDao=proxy(AgentDao.class,(method,args)->method.equals("getHosts") && Long.valueOf(88).equals(args[0])?Map.of("reported-1",host):null);
            manager.tokenService=proxy(TokenService.class,(method,args)->{if(method.equals("generateToken")){claims=(Map<String,Object>)args[0];return "signed";}return null;});
        }
    }
}
