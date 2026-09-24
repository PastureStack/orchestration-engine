package io.cattle.platform.process.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Network;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.NetworkRecord;
import io.cattle.platform.engine.idempotent.IdempotentRetryException;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AccountPurgeNetworkTest {

    @Test
    public void retryFinishesRemovingNetworkEvenAfterRemovedTimestampWasWritten() {
        AccountRecord account = account(42L);
        NetworkRecord network = network(account, CommonStatesConstants.REQUESTED, null);
        RecordingAccountPurge purge = new RecordingAccountPurge(true);
        purge.setObjectManager(networkStore(account, network));

        try {
            purge.removeNetworks(account, null);
            fail("Expected the first attempt to be retried");
        } catch (IdempotentRetryException expected) {
            assertEquals(CommonStatesConstants.REMOVING, network.getState());
        }

        purge.removeNetworks(account, null);
        assertEquals(CommonStatesConstants.REMOVED, network.getState());
        assertEquals(2, purge.removeCalls);

        purge.removeNetworks(account, null);
        assertEquals("A completed network must not be removed twice", 2, purge.removeCalls);
    }

    @Test
    public void alreadyRemovedAndPurgedNetworksAreNotReprocessed() {
        AccountRecord account = account(42L);
        Date removedAt = new Date();
        RecordingAccountPurge purge = new RecordingAccountPurge(false);
        purge.setObjectManager(networkStore(account,
                network(account, CommonStatesConstants.REMOVED, removedAt),
                network(account, CommonStatesConstants.PURGED, removedAt)));

        purge.removeNetworks(account, null);
        assertEquals(0, purge.removeCalls);
    }

    @Test
    public void removingNetworkWithoutRemovedTimestampUsesOriginalQueryOnly() {
        AccountRecord account = account(42L);
        NetworkRecord network = network(account, CommonStatesConstants.REMOVING, null);
        RecordingAccountPurge purge = new RecordingAccountPurge(false);
        purge.setObjectManager(networkStore(account, network));

        purge.removeNetworks(account, null);
        assertEquals(1, purge.removeCalls);
        assertEquals(CommonStatesConstants.REMOVED, network.getState());
    }

    private static AccountRecord account(Long id) {
        AccountRecord account = new AccountRecord();
        account.setId(id);
        return account;
    }

    private static NetworkRecord network(Account account, String state, Date removedAt) {
        NetworkRecord network = new NetworkRecord();
        network.setAccountId(account.getId());
        network.setState(state);
        network.setRemoved(removedAt);
        return network;
    }

    private static ObjectManager networkStore(final Account account, final NetworkRecord... networks) {
        return (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class }, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (!"find".equals(method.getName()) || args.length != 3) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        assertEquals(Network.class, args[0]);
                        Object[] conditions = (Object[]) args[2];
                        assertEquals(ObjectMetaDataManager.ACCOUNT_FIELD, conditions[1]);
                        assertEquals(account.getId(), conditions[2]);

                        List<Network> result = new ArrayList<Network>();
                        for (Network network : Arrays.asList(networks)) {
                            if (ObjectMetaDataManager.REMOVED_FIELD.equals(args[1])) {
                                assertNull(conditions[0]);
                                if (network.getRemoved() == null) {
                                    result.add(network);
                                }
                            } else if (ObjectMetaDataManager.STATE_FIELD.equals(args[1])) {
                                assertEquals(CommonStatesConstants.REMOVING, conditions[0]);
                                if (CommonStatesConstants.REMOVING.equals(network.getState())) {
                                    result.add(network);
                                }
                            } else {
                                fail("Unexpected network query: " + args[1]);
                            }
                        }
                        return result;
                    }
                });
    }

    private static class RecordingAccountPurge extends AccountPurge {
        private final boolean retryFirstRemoval;
        private int removeCalls;

        private RecordingAccountPurge(boolean retryFirstRemoval) {
            this.retryFirstRemoval = retryFirstRemoval;
        }

        @Override
        protected ExitReason deactivateThenRemove(Object obj, Map<String, Object> data) {
            Network network = (Network) obj;
            removeCalls++;
            if (retryFirstRemoval && removeCalls == 1) {
                network.setState(CommonStatesConstants.REMOVING);
                network.setRemoved(new Date());
                throw new IdempotentRetryException();
            }
            network.setState(CommonStatesConstants.REMOVED);
            return null;
        }
    }
}
