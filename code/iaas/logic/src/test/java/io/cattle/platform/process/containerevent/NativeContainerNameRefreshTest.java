package io.cattle.platform.process.containerevent;

import static io.cattle.platform.process.containerevent.ContainerEventCreate.NativeNameRefreshResult.*;
import static org.junit.Assert.*;

import io.cattle.platform.agent.AgentLocator;
import io.cattle.platform.agent.RemoteAgent;
import io.cattle.platform.core.dao.InstanceDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.tables.records.AgentRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.netflix.config.ConfigurationManager;

public class NativeContainerNameRefreshTest {
    static final String DOCKER_ID = "a".repeat(64);
    TestHandler handler;
    AgentRecord agent;
    HostRecord host;
    InstanceRecord row;
    boolean candidate;
    boolean cas;
    int updates;
    int notifications;
    String writtenName;
    Event notification;
    Object previousManageContainers;

    @Before
    public void setup() {
        previousManageContainers = ConfigurationManager.getConfigInstance().getProperty("manage.nonrancher.containers");
        ConfigurationManager.getConfigInstance().setProperty("manage.nonrancher.containers", true);
        handler = new TestHandler();
        agent = new AgentRecord(); agent.setId(1L);
        host = new HostRecord(); host.setId(1L); host.setAgentId(1L); host.setAccountId(5L);
        row = new InstanceRecord(); row.setId(40L); row.setAccountId(5L); row.setExternalId(DOCKER_ID);
        row.setName("original"); row.setState("stopped");
        candidate = true; cas = true;
        handler.setObjects(proxy(ObjectManager.class, (method, args) -> {
            if (method.equals("loadResource")) return args[0] == Agent.class ? agent : host;
            throw new AssertionError("Unexpected object mutation: " + method);
        }));
        handler.lockManager = proxy(LockManager.class, (method, args) -> ((LockCallback<?>) args[1]).doWithLock());
        handler.instanceDao = proxy(InstanceDao.class, (method, args) -> {
            if (method.equals("getNativeContainerForNameRefresh")) return candidate ? row : null;
            if (method.equals("updateNativeContainerName")) {
                updates++; writtenName = (String) args[4];
                assertSame(row, args[0]); assertSame(agent, args[1]); assertSame(host, args[2]);
                assertEquals("host-uuid", args[3]);
                return cas;
            }
            throw new AssertionError("Unexpected lifecycle call: " + method);
        });
        handler.eventService = proxy(EventService.class, (method, args) -> {
            assertEquals("publish", method); notifications++; notification = (Event) args[0]; return true;
        });
        handler.inspect = map("Id", DOCKER_ID, "Name", "/rollback-current");
    }

    @After
    public void restoreConfiguration() {
        if (previousManageContainers == null) {
            ConfigurationManager.getConfigInstance().clearProperty("manage.nonrancher.containers");
        } else {
            ConfigurationManager.getConfigInstance().setProperty("manage.nonrancher.containers", previousManageContainers);
        }
    }

    @Test
    public void currentInspectNameNotUuidHintIsWrittenOnceAndNotificationIsScoped() {
        assertEquals(UPDATED, refresh("untrusted-old-hint"));
        assertEquals("rollback-current", writtenName);
        assertEquals(1, updates); assertEquals(1, notifications);
        assertEquals("40", notification.getResourceId());
        assertEquals(5L, ((Map<?, ?>) notification.getData()).get("accountId"));
        assertEquals("original", row.getName());
        row.setName("rollback-current");
        assertEquals(UNCHANGED, refresh("rollback-current"));
        assertEquals(1, updates); assertEquals(1, notifications); assertEquals(1, handler.inspections);
    }

    @Test
    public void runningContainerAndSecondDistinctFullIdUseExactIndependentAuthority() {
        row.setState("running");
        assertEquals(UPDATED, handler.refreshNativeContainerName(1, 1, "host-uuid", DOCKER_ID,
                "hint", "running", new HashMap<>()));
        String second = "b".repeat(64);
        row = new InstanceRecord(); row.setId(41L); row.setAccountId(5L); row.setExternalId(second);
        row.setName("original"); row.setState("running");
        handler.inspect = map("Id", second, "Name", "/rollback-second");
        assertEquals(UPDATED, handler.refreshNativeContainerName(1, 1, "host-uuid", second,
                "hint", "running", new HashMap<>()));
        assertEquals(second, handler.inspectedId);
        assertEquals("41", notification.getResourceId());
        assertEquals(2, updates);
    }

    @Test
    public void absentCandidateOrForeignHostCannotInspectOrCreate() {
        candidate = false;
        assertEquals(CANDIDATE_UNVERIFIED, refresh("hint"));
        candidate = true; host.setAgentId(2L);
        assertEquals(SOURCE_UNVERIFIED, refresh("hint"));
        assertEquals(0, handler.inspections); assertEquals(0, updates); assertEquals(0, notifications);
    }

    @Test
    public void sourceAccountChangedAfterLockSelectionCannotRefreshForeignInstance() {
        HostRecord moved = new HostRecord();
        moved.setId(1L); moved.setAgentId(1L); moved.setAccountId(6L);
        int[] hostLoads = {0};
        handler.setObjects(proxy(ObjectManager.class, (method, args) -> {
            assertEquals("loadResource", method);
            return args[0] == Agent.class ? agent : (++hostLoads[0] == 1 ? host : moved);
        }));
        handler.instanceDao = proxy(InstanceDao.class, (method, args) -> {
            throw new AssertionError("Reparented host must not reach candidate lookup");
        });
        assertEquals(SOURCE_UNVERIFIED, refresh("hint"));
        assertEquals(0, handler.inspections); assertEquals(0, updates); assertEquals(0, notifications);
    }

    @Test
    public void actualAgentInspectRequestUsesOnlyFullDockerIdNeverNameFallback() {
        ContainerEventCreate actual = new ContainerEventCreate();
        RemoteAgent remote = proxy(RemoteAgent.class, (method, args) -> {
            assertEquals("callSync", method);
            Event request = (Event) args[0];
            assertEquals("compute.instance.inspect", request.getName());
            assertEquals("instanceInspect", request.getResourceType());
            Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) request.getData()).get("instanceInspect");
            assertEquals("docker", data.get("kind"));
            assertEquals(DOCKER_ID, data.get("id"));
            assertFalse(data.containsKey("name"));
            return EventVO.newEvent(request.getName()).withData(map("instanceInspect", handler.inspect));
        });
        actual.agentLocator = proxy(AgentLocator.class, (method, args) -> {
            assertEquals("lookupAgent", method); assertEquals(1L, args[0]); return remote;
        });
        assertEquals(handler.inspect, actual.inspectNativeNameById(1L, DOCKER_ID));
    }

    @Test
    public void managedLabelShortIdOrDifferentStateCannotReachInspection() {
        Map<String, String> labels = new HashMap<>(); labels.put("io.rancher.container.uuid", "logical-uuid");
        assertEquals(CANDIDATE_UNVERIFIED, handler.refreshNativeContainerName(1, 1, "host-uuid", DOCKER_ID,
                "hint", "stopped", labels));
        assertEquals(CANDIDATE_UNVERIFIED, handler.refreshNativeContainerName(1, 1, "host-uuid", "a".repeat(12),
                "hint", "stopped", new HashMap<>()));
        row.setState("running");
        assertEquals(CANDIDATE_UNVERIFIED, refresh("hint"));
        assertEquals(0, handler.inspections); assertEquals(0, updates);
    }

    @Test
    public void inspectFailureWrongIdOrMalformedNameNeverWritesHint() {
        handler.inspect = null;
        assertEquals(INSPECT_UNAVAILABLE, refresh("hint"));
        handler.inspect = map("Id", "b".repeat(64), "Name", "/wrong");
        assertEquals(INSPECT_ID_MISMATCH, refresh("hint"));
        for (Object name : new Object[] {null, false, "", "/", "//invalid", "line\nbreak", "a".repeat(256)}) {
            handler.inspect = map("Id", DOCKER_ID, "Name", name);
            assertEquals(INSPECT_NAME_INVALID, refresh("hint"));
        }
        assertEquals(0, updates); assertEquals(0, notifications);
    }

    @Test
    public void casMissDoesNotNotifyOrRetryAndDelayedHintCannotRollNameBack() {
        cas = false;
        assertEquals(CAS_MISS, refresh("hint"));
        assertEquals(1, updates); assertEquals(0, notifications);
        row.setName("rollback-current");
        assertEquals(UNCHANGED, refresh("stale-ping-hint"));
        assertEquals(1, updates); assertEquals(0, notifications);
    }

    @Test
    public void notificationFailurePreservesKnownCommittedUpdateWithoutRetry() {
        handler.eventService = proxy(EventService.class, (method, args) -> {
            assertEquals("publish", method); notifications++; throw new IllegalStateException("synthetic");
        });
        assertEquals(UPDATED_NOTIFICATION_FAILED, refresh("hint"));
        assertEquals(1, updates); assertEquals(1, notifications);
        row.setName("rollback-current");
        assertEquals(UNCHANGED, refresh("rollback-current"));
        assertEquals(1, updates); assertEquals(1, notifications);
        row.setName("original");
        handler.eventService = proxy(EventService.class, (method, args) -> {
            assertEquals("publish", method); notifications++; return false;
        });
        assertEquals(UPDATED_NOTIFICATION_FAILED, refresh("hint"));
        assertEquals(2, updates); assertEquals(2, notifications);
    }

    private ContainerEventCreate.NativeNameRefreshResult refresh(String hint) {
        return handler.refreshNativeContainerName(1, 1, "host-uuid", DOCKER_ID, hint, "stopped", new HashMap<>());
    }

    interface Call { Object invoke(String method, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> call.invoke(method.getName(), args)));
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static class TestHandler extends ContainerEventCreate {
        Map<String, Object> inspect;
        int inspections;
        String inspectedId;
        void setObjects(ObjectManager objects) { this.objectManager = objects; }
        @Override
        protected Map<String, Object> inspectNativeNameById(long agentId, String externalId) {
            assertEquals(1L, agentId); inspections++; inspectedId = externalId; return inspect;
        }
    }
}
