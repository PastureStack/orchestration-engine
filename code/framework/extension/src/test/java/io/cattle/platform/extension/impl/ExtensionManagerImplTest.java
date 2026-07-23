package io.cattle.platform.extension.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.Test;

public class ExtensionManagerImplTest {

    @Test
    public void firstReturnsTypedProxyForFirstRegisteredExtension() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("second"), "second");
        manager.start();

        Greeter greeter = manager.first("test.greeter", Greeter.class);

        assertTrue(Greeter.class.isInstance(greeter));
        assertEquals("hello first", greeter.greet());
    }

    @Test
    public void firstStringLookupReturnsTypedProxyForFirstRegisteredExtension() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("second"), "second");
        manager.start();

        Greeter greeter = manager.first("test.greeter", Greeter.class.getName());

        assertTrue(Greeter.class.isInstance(greeter));
        assertEquals("hello first", greeter.greet());
    }

    @Test
    public void firstStringLookupProxyCreatedBeforeStartUsesLiveExtensionList() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        Greeter greeter = manager.first("test.greeter", Greeter.class.getName());

        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.start();

        assertTrue(Greeter.class.isInstance(greeter));
        assertEquals("hello first", greeter.greet());
    }

    @Test
    public void firstStringLookupRejectsMissingClassName() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();

        try {
            manager.first("test.greeter", "missing.test.Greeter");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("missing.test.Greeter"));
            return;
        }

        throw new AssertionError("Expected missing class name to fail");
    }

    @Test
    public void listViewCreatedBeforeStartReflectsStartedExtensions() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        List<?> list = manager.list("test.greeter");

        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.start();

        assertEquals(1, list.size());
        assertTrue(list.get(0) instanceof Greeter);
        assertEquals("hello first", ((Greeter) list.get(0)).greet());
    }

    @Test
    public void typedExtensionListKeepsLiveViewBehavior() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        List<Greeter> greeters = manager.getExtensionList("test.greeter", Greeter.class);

        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.start();

        assertEquals(1, greeters.size());
        assertEquals("hello first", greeters.get(0).greet());
    }

    @Test
    public void typedAndErasedExtensionListShareSameLiveViewForKey() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        List<Greeter> typed = manager.getExtensionList("test.greeter", Greeter.class);
        List<?> erased = manager.list("test.greeter");

        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.start();

        assertSame(erased, typed);
        assertEquals(1, typed.size());
        assertEquals("hello first", typed.get(0).greet());
    }

    @Test
    public void nullTypeExtensionListKeepsLiveViewBehavior() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        List<?> greeters = manager.getExtensionList("test.greeter", null);

        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");
        manager.start();

        assertEquals(1, greeters.size());
        assertTrue(greeters.get(0) instanceof Greeter);
        assertEquals("hello first", Greeter.class.cast(greeters.get(0)).greet());
    }

    @Test
    public void getExtensionListRejectsDifferentRegisteredType() {
        ExtensionManagerImpl manager = new ExtensionManagerImpl();
        manager.addObject("test.greeter", Greeter.class, new GreeterImpl("first"), "first");

        try {
            manager.getExtensionList("test.greeter", String.class);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("test.greeter"));
            return;
        }

        throw new AssertionError("Expected type mismatch to fail");
    }

    @Test
    public void extensionErasureCastBoundaryStaysPrivate() throws Exception {
        Method extensionErasureCast = ExtensionManagerImpl.class.getDeclaredMethod("extensionErasureCast", Object.class);
        GreeterImpl greeter = new GreeterImpl("private");

        assertTrue(Modifier.isPrivate(extensionErasureCast.getModifiers()));
        assertTrue(Modifier.isStatic(extensionErasureCast.getModifiers()));
        extensionErasureCast.setAccessible(true);
        assertSame(greeter, extensionErasureCast.invoke(null, greeter));
    }

    public static interface Greeter {
        String greet();
    }

    private static class GreeterImpl implements Greeter {
        private final String name;

        GreeterImpl(String name) {
            this.name = name;
        }

        @Override
        public String greet() {
            return "hello " + name;
        }
    }
}
