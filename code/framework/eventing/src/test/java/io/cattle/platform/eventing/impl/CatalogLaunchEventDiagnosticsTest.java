package io.cattle.platform.eventing.impl;

import static org.junit.Assert.*;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.json.JacksonJsonMapper;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.slf4j.Logger;

public class CatalogLaunchEventDiagnosticsTest {
    @Test public void privateProofIsTransportedButNeverIncludedInEventDiagnostics() {
        Fixture service = new Fixture();
        EventVO<Object> event = EventVO.newEvent("stack.create;handler=compose-executor");
        event.setData(Map.of("catalogLaunchDelegation", Map.of("proof", "cld1.synthetic-private-proof"), "password", "synthetic-human-private"));
        assertTrue(service.publish(event));
        assertTrue(service.wire.contains("cld1.synthetic-private-proof"));
        assertFalse(event.toString().contains("synthetic-private"));
        assertFalse(event.toString().contains("synthetic-human"));
        assertFalse(service.logs.toString().contains("cld1."));
        service.receive(service.wire);
        assertEquals(1, service.received); assertFalse(service.logs.toString().contains("cld1."));
        service.receive(service.wire.substring(0, service.wire.length() - 1));
        assertEquals(1, service.received); assertFalse(service.logs.toString().contains("synthetic-human"));
        service.fail = true; assertFalse(service.publish(event));
        assertFalse(service.logs.toString().contains("cld1."));
    }

    @Test public void ordinaryEventLoggingAndWireAreUnchanged() {
        Fixture service = new Fixture(); EventVO<Object> event = EventVO.newEvent("ordinary.event"); event.setData(Map.of("name", "harmless-visible"));
        assertTrue(service.publish(event)); assertTrue(event.toString().contains("harmless-visible"));
        assertTrue(service.logs.getFirst().contains("harmless-visible")); service.receive(service.wire);
        assertTrue(service.logs.getLast().contains("harmless-visible")); assertEquals(1, service.received);
    }

    private static class Fixture extends AbstractThreadPoolingEventService {
        final List<String> logs = new ArrayList<>(); String wire; boolean fail; int received;
        final Logger logger = (Logger) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Logger.class }, (p, method, args) -> {
            if (method.getName().equals("debug") && args != null && args.length > 0) logs.add((String) args[0]);
            return method.getReturnType() == boolean.class ? true : null;
        });
        Fixture() { setJsonMapper(new JacksonJsonMapper()); }
        void receive(String value) { onEvent("test", null, value); }
        @Override protected void increment(Event event, boolean request) { }
        @Override protected Logger getEventLogOut() { return logger; }
        @Override protected Logger getEventLogIn() { return logger; }
        @Override protected boolean doPublish(String name, Event event, String value) throws IOException {
            wire = value; if (fail) throw new IOException("cld1.synthetic-private-proof"); return true;
        }
        @Override protected void onEvent(Event event) { received++; }
        @Override protected void doSubscribe(String name, SettableFuture<?> future) { }
        @Override protected void doUnsubscribe(String name) { }
        @Override protected void disconnect() { }
    }
}
