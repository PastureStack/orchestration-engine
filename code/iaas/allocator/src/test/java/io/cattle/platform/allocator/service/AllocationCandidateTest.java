package io.cattle.platform.allocator.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

import io.cattle.platform.object.ObjectManager;

public class AllocationCandidateTest {

    @Test
    public void loadResourceUsesClassCastAndCachesLoadedResource() {
        SampleResource resource = new SampleResource();
        AtomicInteger loadCount = new AtomicInteger();
        Map<Pair<Class<?>, Long>, Object> resources = new HashMap<Pair<Class<?>, Long>, Object>();
        AllocationCandidate candidate = candidateWith(objectManagerReturning(resource, loadCount), resources);

        SampleResource first = candidate.loadResource(SampleResource.class, 7L);
        SampleResource second = candidate.loadResource(SampleResource.class, 7L);

        assertSame(resource, first);
        assertSame(resource, second);
        assertEquals(1, loadCount.get());
        assertEquals(1, resources.size());
    }

    @Test
    public void loadResourceReturnsNullForNullIdWithoutCallingObjectManager() {
        AtomicInteger loadCount = new AtomicInteger();
        AllocationCandidate candidate = candidateWith(objectManagerReturning(new SampleResource(), loadCount),
                new HashMap<Pair<Class<?>, Long>, Object>());

        assertNull(candidate.loadResource(SampleResource.class, null));
        assertEquals(0, loadCount.get());
    }

    @Test(expected = ClassCastException.class)
    public void loadResourceRejectsMismatchedCachedResourceType() {
        Map<Pair<Class<?>, Long>, Object> resources = new HashMap<Pair<Class<?>, Long>, Object>();
        AllocationCandidate candidate = candidateWith(objectManagerReturning(new Object(), new AtomicInteger()), resources);

        candidate.loadResource(SampleResource.class, 7L);
    }

    private AllocationCandidate candidateWith(ObjectManager objectManager, Map<Pair<Class<?>, Long>, Object> resources) {
        AllocationCandidate candidate = new AllocationCandidate();
        candidate.objectManager = objectManager;
        candidate.resources = resources;
        return candidate;
    }

    private ObjectManager objectManagerReturning(final Object resource, final AtomicInteger loadCount) {
        return ObjectManager.class.cast(Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class }, (proxy, method, args) -> {
                    if ("loadResource".equals(method.getName()) && args.length == 2 && args[1] instanceof Long) {
                        loadCount.incrementAndGet();
                        return resource;
                    }
                    throw new UnsupportedOperationException(method.toString());
                }));
    }

    private static class SampleResource {
    }
}
