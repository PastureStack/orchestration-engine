package io.cattle.platform.hazelcast.membership;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

import org.junit.Test;

import com.hazelcast.cluster.Address;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.spi.discovery.DiscoveryStrategy;
import com.hazelcast.spi.discovery.SimpleDiscoveryNode;

public class DBDiscoveryFactoryTest {

    @Test
    public void returnsDbDiscoveryStrategyTypeAndNoConfigurationProperties() {
        DBDiscoveryFactory factory = new DBDiscoveryFactory();

        assertSame(DBDiscovery.class, factory.getDiscoveryStrategyType());
        assertTrue(factory.getConfigurationProperties().isEmpty());
    }

    @Test
    public void newDiscoveryStrategyKeepsHazelcastSpiRawComparableSignature() throws Exception {
        Method method = DBDiscoveryFactory.class.getMethod("newDiscoveryStrategy",
                DiscoveryNode.class, ILogger.class, Map.class);
        Type propertiesType = method.getGenericParameterTypes()[2];

        assertTrue(propertiesType instanceof ParameterizedType);
        ParameterizedType parameterizedType = ParameterizedType.class.cast(propertiesType);

        assertSame(Map.class, parameterizedType.getRawType());
        assertEquals("java.lang.String", parameterizedType.getActualTypeArguments()[0].getTypeName());
        assertEquals("java.lang.Comparable", parameterizedType.getActualTypeArguments()[1].getTypeName());
    }

    @Test
    public void newDiscoveryStrategySetsSelfNodeAndChecksIn() throws Exception {
        DBDiscoveryFactory factory = new DBDiscoveryFactory();
        TestDBDiscovery discovery = new TestDBDiscovery();
        DiscoveryNode self = new SimpleDiscoveryNode(new Address("127.0.0.1", 5701));
        factory.discovery = discovery;

        DiscoveryStrategy result = factory.newDiscoveryStrategy(self, null, null);

        assertSame(discovery, result);
        assertSame(self, discovery.getSelfNode());
        assertSame(self, discovery.selfNodeAtCheckin);
        assertSame(discovery, factory.discovery);
    }

    @Test
    public void newDiscoveryStrategyWrapsCheckinFailure() {
        DBDiscoveryFactory factory = new DBDiscoveryFactory();
        TestDBDiscovery discovery = new TestDBDiscovery();
        IOException failure = new IOException("checkin failed");
        discovery.failure = failure;
        factory.discovery = discovery;

        try {
            factory.newDiscoveryStrategy(null, null, null);
        } catch (IllegalStateException e) {
            assertSame(failure, e.getCause());
            return;
        }

        throw new AssertionError("Expected IllegalStateException");
    }

    private static class TestDBDiscovery extends DBDiscovery {
        private Exception failure;
        private DiscoveryNode selfNodeAtCheckin;

        @Override
        public synchronized void checkin() throws Exception {
            if (failure != null) {
                throw failure;
            }
            selfNodeAtCheckin = getSelfNode();
        }
    }

}
