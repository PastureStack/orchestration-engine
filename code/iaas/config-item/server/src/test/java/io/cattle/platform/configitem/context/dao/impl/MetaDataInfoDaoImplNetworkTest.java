package io.cattle.platform.configitem.context.dao.impl;

import static io.cattle.platform.core.model.tables.NetworkTable.NETWORK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.configitem.context.dao.MetaDataInfoDao;
import io.cattle.platform.configitem.context.data.metadata.common.MetaHelperInfo;
import io.cattle.platform.configitem.context.data.metadata.common.NetworkMetaData;
import io.cattle.platform.core.constants.NetworkConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.object.util.DataUtils;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jooq.Record5;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.Test;

public class MetaDataInfoDaoImplNetworkTest {

    @Test
    public void preservesDefaultAndHostPortFlagsIndependently() {
        assertNetworkFlags(false, false);
        assertNetworkFlags(false, true);
        assertNetworkFlags(true, false);
        assertNetworkFlags(true, true);
    }

    private static void assertNetworkFlags(boolean isDefault, boolean hostPorts) {
        long accountId = 41L;
        long networkId = 73L;

        AccountRecord account = new AccountRecord();
        account.setId(accountId);
        account.setUuid("environment-uuid");
        account.setDefaultNetworkId(isDefault ? networkId : networkId + 1L);

        Map<Long, Account> accounts = new HashMap<Long, Account>();
        accounts.put(accountId, account);
        MetaHelperInfo helperInfo = new MetaHelperInfo(account, accounts,
                Collections.<Long>emptySet(), Collections.<Long>emptySet(), emptyMetadataDao(), null);

        Map<String, Object> data = new HashMap<String, Object>();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put(NetworkConstants.FIELD_HOST_PORTS, hostPorts);
        data.put(DataUtils.FIELDS, fields);
        Record5<String, String, Long, Long, Map<String, Object>> record = DSL
                .using(SQLDialect.MARIADB)
                .newRecord(NETWORK.NAME, NETWORK.UUID, NETWORK.ACCOUNT_ID, NETWORK.ID, NETWORK.DATA);
        record.values("network-name", "network-uuid", accountId, networkId, data);

        CapturingMetaDataInfoDao dao = new CapturingMetaDataInfoDao();
        dao.fetchNetwork(helperInfo, OutputStream.nullOutputStream(), record);

        assertTrue(dao.written instanceof NetworkMetaData);
        NetworkMetaData metadata = (NetworkMetaData) dao.written;
        assertEquals(isDefault, metadata.isIs_default());
        assertEquals(hostPorts, metadata.isHost_ports());
    }

    private static MetaDataInfoDao emptyMetadataDao() {
        return (MetaDataInfoDao) Proxy.newProxyInstance(
                MetaDataInfoDao.class.getClassLoader(),
                new Class<?>[] { MetaDataInfoDao.class },
                (proxy, method, args) -> {
                    if (Map.class.isAssignableFrom(method.getReturnType())) {
                        return Collections.emptyMap();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CapturingMetaDataInfoDao extends MetaDataInfoDaoImpl {
        private Object written;

        @Override
        protected void writeToJson(OutputStream os, Object data) {
            written = data;
        }
    }
}
