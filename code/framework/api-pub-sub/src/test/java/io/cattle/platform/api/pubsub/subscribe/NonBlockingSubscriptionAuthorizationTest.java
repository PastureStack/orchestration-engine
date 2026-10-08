package io.cattle.platform.api.pubsub.subscribe;

import static org.junit.Assert.*;
import io.cattle.platform.eventing.EventListener;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NonBlockingSubscriptionAuthorizationTest {
    @Before public void setup(){ApiContext.newContext().setIdFormatter(new IdentityFormatter());ApiContext.getContext().setPolicy("original-policy");}
    @After public void cleanup(){ApiContext.remove();}

    @Test public void handshakeDenialHappensBeforeWriterUpgrade() throws Exception {
        Handler handler=new Handler();handler.subscriptionAuthorizations=List.of(request->{throw new IllegalStateException("revoked");});
        assertThrows(IllegalStateException.class,()->handler.subscribe(List.of("resource.change"),new ApiRequest(null,null),true));
        assertEquals(0,handler.upgrades);
    }

    @Test public void livePingDenialClosesWriterAndUnsubscribesWithoutBody() throws Exception {
        Handler handler=new Handler();AtomicBoolean revoked=new AtomicBoolean(false);
        handler.subscriptionAuthorizations=List.of(request->event->{if(revoked.get())throw new IllegalStateException("revoked");return "live-policy";});
        handler.subscribe(List.of("resource.change"),new ApiRequest(null,null),true);
        EventVO<Object> ping=new EventVO<>();ping.setName("ping");
        handler.listener.onEvent(ping);assertEquals(1,handler.writes);assertEquals("live-policy",handler.usedPolicy);
        revoked.set(true);handler.listener.onEvent(ping);
        assertEquals(1,handler.writes);assertTrue(handler.writer.closed);assertEquals(1,handler.unsubscribes);
    }

    @Test public void deniedRowIsFilteredBeforePostProcessing() throws Exception {
        Handler handler=new Handler();handler.subscriptionAuthorizations=List.of(request->event->null);
        handler.subscribe(List.of("resource.change"),new ApiRequest(null,null),true);
        handler.listener.onEvent(new EventVO<>());
        assertEquals(0,handler.postProcesses);assertEquals(0,handler.writes);assertFalse(handler.writer.closed);
    }

    @Test public void legacyNoSessionUsesOriginalPolicy() throws Exception {
        Handler handler=new Handler();handler.subscriptionAuthorizations=List.of(request->null);
        handler.subscribe(List.of("resource.change"),new ApiRequest(null,null),true);
        handler.listener.onEvent(new EventVO<>());
        assertEquals("original-policy",handler.usedPolicy);assertEquals(1,handler.writes);
    }

    static class Handler extends NonBlockingSubscriptionHandler {
        int upgrades,writes,postProcesses,unsubscribes;Object usedPolicy;EventListener listener;final Writer writer=new Writer();
        Handler(){
            super(new SubscriptionSettings(){public long pingIntervalMillis(){return 5;}public int maxPings(){return 2;}});
            eventService=(EventService)Proxy.newProxyInstance(EventService.class.getClassLoader(),new Class<?>[]{EventService.class},(proxy,method,args)->{
                if(method.getName().equals("unsubscribe"))unsubscribes++;return null;
            });
        }
        @Override protected MessageWriter getMessageWriter(ApiRequest request){upgrades++;return writer;}
        @Override protected Future<?> subscribe(Collection<String> names,EventListener listener,MessageWriter writer,AtomicBoolean disconnect,Object lock,boolean strip){
            this.listener=listener;return CompletableFuture.completedFuture(null);
        }
        @Override protected boolean postProcess(EventVO<Object> event,IdFormatter formatter,ApiRequest request,Object policy){postProcesses++;usedPolicy=policy;return true;}
        @Override protected void write(Event event,MessageWriter writer,Object lock,boolean strip,EventListener listener,AtomicBoolean disconnect){writes++;}
    }
    static class Writer implements MessageWriter{boolean closed;public void write(String message,Object lock){}public void close(){closed=true;}}
}
