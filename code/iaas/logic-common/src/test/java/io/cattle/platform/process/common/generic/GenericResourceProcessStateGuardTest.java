package io.cattle.platform.process.common.generic;

import static org.junit.Assert.*;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessStateTransitionGuard;
import io.cattle.platform.engine.process.impl.ResourceStatesDefinition;
import io.cattle.platform.object.ObjectManager;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GenericResourceProcessStateGuardTest {
    @Test public void noExtensionPreservesOriginalStateWrite() {
        Resource resource = new Resource(); AtomicInteger writes = new AtomicInteger();
        TestState state = new TestState(resource, writes, List.of());
        assertTrue(state.write());
        assertEquals("activating", resource.getState()); assertEquals(1, writes.get());
    }

    @Test public void extensionWrapsReloadAndActualWriteForEveryIdentity() {
        Resource resource = new Resource(); AtomicInteger writes = new AtomicInteger(), calls = new AtomicInteger();
        TestState state = new TestState(resource, writes, List.of((target, field, value, next) -> {
            calls.incrementAndGet(); assertSame(resource,target); assertEquals("state",field); assertEquals("activating",value);
            assertEquals(0,writes.get()); return next.get();
        }));
        assertTrue(state.write()); assertEquals(1,calls.get()); assertEquals(1,writes.get());
    }

    @Test public void deniedExtensionCannotWriteState() {
        Resource resource = new Resource(); AtomicInteger writes = new AtomicInteger();
        TestState state = new TestState(resource, writes, List.of((target, field, value, next) -> false));
        assertFalse(state.write()); assertEquals("inactive",resource.getState()); assertEquals(0,writes.get());
    }

    public static class Resource {
        private String state="inactive";
        public String getState(){return state;} public void setState(String value){state=value;}
    }

    private static class TestState extends GenericResourceProcessState {
        TestState(Resource resource, AtomicInteger writes, List<ProcessStateTransitionGuard> guards) {
            super(null,new ResourceStatesDefinition(),new LaunchConfiguration("service.activate","service","2",1L,null,Map.of()),manager(resource,writes),guards);
        }
        boolean write(){return setState(false,"inactive","activating");}
    }
    private static ObjectManager manager(Resource resource, AtomicInteger writes) {
        return (ObjectManager)Proxy.newProxyInstance(GenericResourceProcessStateGuardTest.class.getClassLoader(),new Class<?>[]{ObjectManager.class},(p,m,a)->{
            if(m.getName().equals("loadResource")||m.getName().equals("reload"))return resource;
            if(m.getName().equals("setFields")){resource.setState(((Object[])a[2])[0].toString());writes.incrementAndGet();return resource;}
            throw new UnsupportedOperationException(m.getName());
        });
    }
}
