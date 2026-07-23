package org.apache.cloudstack.configitem.server.impl;

import static org.junit.Assert.*;
import io.cattle.platform.configitem.events.ConfigUpdate;
import io.cattle.platform.configitem.model.Client;
import io.cattle.platform.configitem.model.DefaultItemVersion;
import io.cattle.platform.configitem.model.ItemVersion;
import io.cattle.platform.configitem.registry.impl.ConfigItemRegistryImpl;
import io.cattle.platform.configitem.request.ConfigUpdateRequest;
import io.cattle.platform.configitem.server.impl.ConfigItemServerImpl;
import io.cattle.platform.configitem.version.ConfigItemStatusManager;

import com.google.common.util.concurrent.ListenableFuture;

import org.apache.cloudstack.configitem.server.model.impl.TestRequest;
import org.apache.cloudstack.configitem.server.model.impl.WriteStringConfigItem;
import org.junit.Before;
import org.junit.Test;

public class ConfigItemServerImplTest {

    ConfigItemServerImpl server;
    ConfigItemRegistryImpl registry;
    TestRequest req;
    TestConfigItemStatusManager versionManager;

    @Before
    public void setup() {
        registry = new ConfigItemRegistryImpl();
        versionManager = new TestConfigItemStatusManager();

        server = new ConfigItemServerImpl();
        server.setVersionManager(versionManager);
        server.setItemRegistry(registry);
        req = new TestRequest();
    }

    @Test
    public void test_not_found() throws Exception {
        req.setItemName("missing");
        server.handleRequest(req);

        assertEquals(404, req.getResponseCode());
        assertEquals("", req.getResponseContent());
    }

    @Test
    public void test_write_string() throws Exception {
        registry.register(new WriteStringConfigItem("string", "content"));
        versionManager.assigned = true;
        req.setItemName("string");
        server.handleRequest(req);

        assertEquals(200, req.getResponseCode());
        assertEquals("content", req.getResponseContent());
    }

    @Test
    public void test_applied() throws Exception {
        registry.register(new WriteStringConfigItem("testitem", "content"));
        DefaultItemVersion version = DefaultItemVersion.fromString("000000042-testitem/content");

        versionManager.assigned = true;
        req.setAppliedVersion(version);
        server.handleRequest(req);

        assertSame(req.getClient(), versionManager.appliedClient);
        assertEquals("testitem", versionManager.appliedItemName);
        assertEquals(42, versionManager.appliedVersion.getRevision());
        assertEquals("testitem/content", versionManager.appliedVersion.getSourceRevision());
        assertEquals(200, req.getResponseCode());
    }

    @Test
    public void test_applied_latest() throws Exception {
        DefaultItemVersion version = DefaultItemVersion.fromString("latest");

        req.setAppliedVersion(version);
        req.setItemName("name1");
        registry.register(new WriteStringConfigItem("name1", "content1"));
        versionManager.assigned = true;
        server.handleRequest(req);

        assertTrue(version.isLatest());
        assertSame(req.getClient(), versionManager.latestClient);
        assertEquals("name1", versionManager.latestItemName);
        assertEquals("name1/content1", versionManager.latestSourceRevision);
        assertEquals(200, req.getResponseCode());
    }

    @Test
    public void test_applied_latest_not_found() throws Exception {
        DefaultItemVersion version = DefaultItemVersion.fromString("latest");

        req.setAppliedVersion(version);
        req.setItemName("name1");
        server.handleRequest(req);

        assertEquals(404, req.getResponseCode());
    }

    private static class TestConfigItemStatusManager implements ConfigItemStatusManager {
        boolean assigned;
        Client appliedClient;
        String appliedItemName;
        ItemVersion appliedVersion;
        Client latestClient;
        String latestItemName;
        String latestSourceRevision;

        @Override
        public ItemVersion getRequestedVersion(Client client, String itemName) {
            return null;
        }

        @Override
        public boolean setApplied(Client client, String itemName, ItemVersion version) {
            appliedClient = client;
            appliedItemName = itemName;
            appliedVersion = version;
            return true;
        }

        @Override
        public void setLatest(Client client, String itemName, String sourceRevision) {
            latestClient = client;
            latestItemName = itemName;
            latestSourceRevision = sourceRevision;
        }

        @Override
        public void setItemSourceVersion(String name, String sourceRevision) {
        }

        @Override
        public boolean isAssigned(Client client, String itemName) {
            return assigned;
        }

        @Override
        public void updateConfig(ConfigUpdateRequest request) {
        }

        @Override
        public ListenableFuture<?> whenReady(ConfigUpdateRequest request) {
            return null;
        }

        @Override
        public void waitFor(ConfigUpdateRequest request) {
        }

        @Override
        public void sync(boolean migration) {
        }

        @Override
        public boolean runUpdateForEvent(String itemName, ConfigUpdate update, Client client, Runnable run) {
            run.run();
            return true;
        }
    }

}
