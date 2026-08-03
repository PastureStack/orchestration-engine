package io.cattle.platform.iaas.api.port;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.agent.AgentLocator;
import io.cattle.platform.agent.RemoteAgent;
import io.cattle.platform.core.addon.PortPreflightInput;
import io.cattle.platform.core.addon.PortPreflightPort;
import io.cattle.platform.core.addon.PortPreflightResult;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.object.ObjectManager;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class PortPreflightServiceTest {

    @Test
    public void requestedHostActiveConflictIsBlocked() {
        PortPreflightResult result = service(hosts(1L, 2L), owners(owner(1L, "running", "0.0.0.0", "tcp")))
                .check(account(), input(1L, false, port("10.0.0.5", "tcp")));

        assertEquals("blocked", result.getStatus());
        assertEquals(Integer.valueOf(0), result.getAvailableHostCount());
        assertEquals("active_port_conflict", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void conflictOnAnotherHostBlocksEnvironmentWideReuse() {
        PortPreflightResult result = service(hosts(1L, 2L), owners(owner(1L, "running", "0.0.0.0", "tcp")))
                .check(account(), input(null, false, port("10.0.0.5", "tcp")));

        assertEquals("blocked", result.getStatus());
        assertEquals(Integer.valueOf(1), result.getAvailableHostCount());
        assertEquals("active_port_conflict", result.getConflicts().get(0).getReasonCode());
        assertEquals("blocked", result.getConflicts().get(0).getSeverity());
    }

    @Test
    public void globalServiceRequiresEveryHostToBeAvailable() {
        PortPreflightInput input = input(null, false, port("0.0.0.0", "tcp"));
        input.setGlobal(Boolean.TRUE);
        PortPreflightResult result = service(hosts(1L, 2L), owners(owner(1L, "running", "10.0.0.5", "tcp")))
                .check(account(), input);

        assertEquals("blocked", result.getStatus());
    }

    @Test
    public void stoppedOwnerProducesWarningWithoutReducingCapacity() {
        PortPreflightResult result = service(hosts(1L), owners(owner(1L, "stopped", "0.0.0.0", "tcp")))
                .check(account(), input(1L, false, port("0.0.0.0", "tcp")));

        assertEquals("warning", result.getStatus());
        assertEquals(Integer.valueOf(1), result.getAvailableHostCount());
        assertEquals("stopped_port_owner", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void tcpAndUdpUseIndependentNamespaces() {
        PortPreflightResult result = service(hosts(1L), owners(owner(1L, "running", "0.0.0.0", "udp")))
                .check(account(), input(1L, false, port("0.0.0.0", "tcp")));

        assertEquals("available", result.getStatus());
    }

    @Test
    public void duplicateRequestedBindingIsBlockedBeforeScheduling() {
        PortPreflightInput input = input(null, false,
                port("0.0.0.0", "tcp"), port("10.0.0.5", "tcp"));
        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList())
                .check(account(), input);

        assertEquals("blocked", result.getStatus());
        assertEquals("duplicate_requested_port", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void startFirstUsesConcurrentBatchCapacityInsteadOfFullServiceScale() {
        PortPreflightInput input = input(null, false, port("0.0.0.0", "tcp"));
        input.setServiceId(Long.valueOf(55L));
        input.setScale(Integer.valueOf(2));
        input.setBatchSize(Integer.valueOf(1));
        input.setStartFirst(Boolean.TRUE);

        PortOwner first = owner(1L, "running", "0.0.0.0", "tcp");
        PortOwner second = owner(2L, "running", "0.0.0.0", "tcp");
        first.serviceId = Long.valueOf(55L);
        second.serviceId = Long.valueOf(55L);
        PortPreflightResult result = service(hosts(1L, 2L, 3L), owners(first, second))
                .check(account(), input);

        assertEquals("available", result.getStatus());
        assertEquals(Integer.valueOf(1), result.getAvailableHostCount());
    }

    @Test
    public void startFirstBlocksWhenConcurrentBatchCannotFit() {
        PortPreflightInput input = input(null, false, port("0.0.0.0", "tcp"));
        input.setServiceId(Long.valueOf(55L));
        input.setScale(Integer.valueOf(2));
        input.setBatchSize(Integer.valueOf(2));
        input.setStartFirst(Boolean.TRUE);

        PortOwner first = owner(1L, "running", "0.0.0.0", "tcp");
        PortOwner second = owner(2L, "running", "0.0.0.0", "tcp");
        first.serviceId = Long.valueOf(55L);
        second.serviceId = Long.valueOf(55L);
        PortPreflightResult result = service(hosts(1L, 2L, 3L), owners(first, second))
                .check(account(), input);

        assertEquals("blocked", result.getStatus());
        assertEquals("insufficient_eligible_hosts", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void startFirstChangedBindingConflictsWithTheCurrentService() {
        PortPreflightInput input = input(null, false, port("0.0.0.0", "tcp"));
        input.setServiceId(Long.valueOf(55L));
        input.setStartFirst(Boolean.TRUE);

        PortOwner current = owner(1L, "running", "10.0.0.5", "tcp");
        current.serviceId = Long.valueOf(55L);

        PortPreflightResult result = service(hosts(1L, 2L), owners(current)).check(account(), input);

        assertEquals("blocked", result.getStatus());
        assertEquals("active_port_conflict", result.getConflicts().get(0).getReasonCode());
        assertEquals("blocked", result.getConflicts().get(0).getSeverity());
    }

    @Test
    public void startFirstStoppedCurrentContainerWarnsWithoutConsumingCapacity() {
        PortPreflightInput input = input(null, false, port("0.0.0.0", "tcp"));
        input.setServiceId(Long.valueOf(55L));
        input.setStartFirst(Boolean.TRUE);

        PortOwner current = owner(1L, "stopped", "0.0.0.0", "tcp");
        current.serviceId = Long.valueOf(55L);

        PortPreflightResult result = service(hosts(1L), owners(current)).check(account(), input);

        assertEquals("warning", result.getStatus());
        assertEquals(Integer.valueOf(1), result.getAvailableHostCount());
        assertEquals("stopped_port_owner", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void upgradeRuntimeProbeIgnoresTheCurrentServiceContainer() {
        PortOwner current = owner(1L, "running", "0.0.0.0", "tcp");
        current.serviceId = Long.valueOf(55L);
        current.externalId = "self-container-id-1234567890";

        Map<String, Object> conflict = new HashMap<String, Object>();
        conflict.put("source", "docker");
        conflict.put("containerId", "self-container-id-1234567890");
        conflict.put("containerName", "web-1");
        conflict.put("state", "running");
        conflict.put("bindAddress", "0.0.0.0");
        conflict.put("publicPort", Integer.valueOf(2201));
        conflict.put("protocol", "tcp");
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("supported", Boolean.TRUE);
        data.put("hostSocketProbeSupported", Boolean.TRUE);
        data.put("conflicts", Collections.singletonList(conflict));
        SettableFuture<Event> completed = SettableFuture.create();
        completed.set(EventVO.<Map<String, Object>>newEvent("host.port.check.reply").withData(data));

        PortPreflightInput input = input(1L, true, port("0.0.0.0", "tcp"));
        input.setServiceId(Long.valueOf(55L));
        PortPreflightResult result = service(hosts(1L), owners(current), completed).check(account(), input);

        assertEquals("available", result.getStatus());
        assertEquals(Integer.valueOf(1), result.getAvailableHostCount());
        assertEquals(Integer.valueOf(0), Integer.valueOf(result.getConflicts().size()));
    }

    @Test
    public void hostNetworkRejectsMisleadingPublishedPortRemapping() {
        PortPreflightInput input = input(1L, false, port("0.0.0.0", "tcp"));
        input.setNetworkMode("host");

        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList())
                .check(account(), input);

        assertEquals("blocked", result.getStatus());
        assertEquals("host_network_ignores_published_port", result.getConflicts().get(0).getReasonCode());
        assertEquals(Integer.valueOf(22), result.getConflicts().get(0).getPrivatePort());
    }

    @Test
    public void hostNetworkChecksExposedPrivatePortWhenNoPublishedPortWasEntered() {
        PortPreflightPort requested = port("0.0.0.0", "tcp");
        requested.setPublicPort(null);
        PortPreflightInput input = input(1L, false, requested);
        input.setNetworkMode("host");
        PortOwner owner = owner(1L, "running", "0.0.0.0", "tcp");
        owner.publicPort = Integer.valueOf(22);

        PortPreflightResult result = service(hosts(1L), owners(owner)).check(account(), input);

        assertEquals("blocked", result.getStatus());
        assertEquals(Integer.valueOf(22), result.getConflicts().get(0).getPublicPort());
    }

    @Test
    public void containerNetworkNamespaceRejectsPublishedHostPort() {
        PortPreflightInput input = input(1L, false, port("0.0.0.0", "tcp"));
        input.setNetworkMode("container:primary");

        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList())
                .check(account(), input);

        assertEquals("blocked", result.getStatus());
        assertEquals("invalid_network_mode_mapping", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void mixedSidekickContainerMappingIsRejectedUsingItsOwnNetworkMode() {
        PortPreflightPort sidekick = port("0.0.0.0", "tcp");
        sidekick.setNetworkMode("container:primary");

        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList())
                .check(account(), input(1L, false, sidekick));

        assertEquals("blocked", result.getStatus());
        assertEquals("invalid_network_mode_mapping", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void mixedManagedAndHostBindingsShareThePhysicalHostNamespace() {
        PortPreflightPort managed = port("0.0.0.0", "tcp");
        managed.setPublicPort(Integer.valueOf(22));
        managed.setPrivatePort(Integer.valueOf(8080));
        managed.setNetworkMode("managed");
        PortPreflightPort host = port("0.0.0.0", "tcp");
        host.setPublicPort(Integer.valueOf(22));
        host.setPrivatePort(Integer.valueOf(22));
        host.setNetworkMode("host");

        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList())
                .check(account(), input(1L, false, managed, host));

        assertEquals("blocked", result.getStatus());
        assertEquals("duplicate_requested_port", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void zeroScaleServiceDoesNotRequireAnEligibleHost() {
        PortPreflightInput input = input(null, false, port("0.0.0.0", "tcp"));
        input.setScale(Integer.valueOf(0));

        PortPreflightResult result = service(Collections.<Host>emptyList(), Collections.<PortOwner>emptyList())
                .check(account(), input);

        assertEquals("available", result.getStatus());
        assertEquals(Integer.valueOf(0), result.getAvailableHostCount());
    }

    @Test
    public void runtimeTimeoutIsUnknownInsteadOfAvailable() {
        SettableFuture<Event> pending = SettableFuture.create();

        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList(), pending)
                .check(account(), input(1L, true, port("0.0.0.0", "tcp")));

        assertEquals("unknown", result.getStatus());
        assertEquals("agent_timeout", result.getConflicts().get(0).getReasonCode());
    }

    @Test
    public void runtimeDockerConflictBlocksRequestedHostAndNamesContainer() {
        Map<String, Object> conflict = new HashMap<String, Object>();
        conflict.put("source", "docker");
        conflict.put("state", "running");
        conflict.put("bindAddress", "0.0.0.0");
        conflict.put("publicPort", Integer.valueOf(2201));
        conflict.put("protocol", "tcp");
        conflict.put("containerName", "manual-web");

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("supported", Boolean.TRUE);
        data.put("hostSocketProbeSupported", Boolean.TRUE);
        data.put("conflicts", Collections.singletonList(conflict));
        Event reply = EventVO.<Map<String, Object>>newEvent("host.port.check.reply").withData(data);
        SettableFuture<Event> completed = SettableFuture.create();
        completed.set(reply);

        PortPreflightResult result = service(hosts(1L), Collections.<PortOwner>emptyList(), completed)
                .check(account(), input(1L, true, port("0.0.0.0", "tcp")));

        assertEquals("blocked", result.getStatus());
        assertEquals("external_docker_conflict", result.getConflicts().get(0).getReasonCode());
        assertEquals("manual-web", result.getConflicts().get(0).getInstanceName());
    }

    private static PortPreflightService service(final List<Host> hosts, final List<PortOwner> owners) {
        PortPreflightService service = new PortPreflightService();
        service.dao = new PortPreflightDao() {
            @Override
            public List<Host> getEligibleHosts(long accountId) {
                return hosts;
            }

            @Override
            public List<PortOwner> getPortOwners(long accountId) {
                return owners;
            }
        };
        service.objectManager = ownedReferenceManager();
        return service;
    }

    private static PortPreflightService service(final List<Host> hosts, final List<PortOwner> owners,
            final ListenableFuture<? extends Event> future) {
        PortPreflightService service = service(hosts, owners);
        final RemoteAgent remoteAgent = (RemoteAgent) Proxy.newProxyInstance(
                RemoteAgent.class.getClassLoader(),
                new Class<?>[] { RemoteAgent.class },
                (proxy, method, args) -> {
                    if ("call".equals(method.getName()) && args != null && args.length == 2) {
                        return future;
                    }
                    if ("getAgentId".equals(method.getName())) {
                        return Long.valueOf(1L);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        service.agentLocator = (AgentLocator) Proxy.newProxyInstance(
                AgentLocator.class.getClassLoader(),
                new Class<?>[] { AgentLocator.class },
                (proxy, method, args) -> {
                    if ("lookupAgent".equals(method.getName())) {
                        return remoteAgent;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return service;
    }

    private static ObjectManager ownedReferenceManager() {
        return (ObjectManager) Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("loadResource".equals(method.getName())
                            && args != null
                            && args.length == 2
                            && Host.class.equals(args[0])
                            && args[1] instanceof Long) {
                        HostRecord host = new HostRecord();
                        host.setId((Long) args[1]);
                        host.setAccountId(Long.valueOf(10));
                        return host;
                    }
                    if ("loadResource".equals(method.getName())
                            && args != null
                            && args.length == 2
                            && Service.class.equals(args[0])
                            && args[1] instanceof Long) {
                        ServiceRecord service = new ServiceRecord();
                        service.setId((Long) args[1]);
                        service.setAccountId(Long.valueOf(10));
                        return service;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AccountRecord account() {
        AccountRecord account = new AccountRecord();
        account.setId(Long.valueOf(10));
        return account;
    }

    private static List<Host> hosts(Long... ids) {
        Host[] hosts = new Host[ids.length];
        for (int i = 0; i < ids.length; i++) {
            HostRecord host = new HostRecord();
            host.setId(ids[i]);
            host.setName("host-" + ids[i]);
            hosts[i] = host;
        }
        return Arrays.asList(hosts);
    }

    private static List<PortOwner> owners(PortOwner... owners) {
        return Arrays.asList(owners);
    }

    private static PortOwner owner(Long hostId, String state, String bindAddress, String protocol) {
        PortOwner owner = new PortOwner();
        owner.hostId = hostId;
        owner.hostName = "host-" + hostId;
        owner.instanceId = Long.valueOf(100 + hostId.longValue());
        owner.instanceName = "container-" + hostId;
        owner.externalId = "container-external-" + hostId;
        owner.state = state;
        owner.bindAddress = bindAddress;
        owner.publicPort = Integer.valueOf(2201);
        owner.privatePort = Integer.valueOf(22);
        owner.protocol = protocol;
        return owner;
    }

    private static PortPreflightInput input(Long requestedHostId, boolean runtimeProbe,
            PortPreflightPort... ports) {
        PortPreflightInput input = new PortPreflightInput();
        input.setNetworkMode("managed");
        input.setRequestedHostId(requestedHostId);
        input.setScale(Integer.valueOf(1));
        input.setBatchSize(Integer.valueOf(1));
        input.setRuntimeProbe(Boolean.valueOf(runtimeProbe));
        input.setPorts(Arrays.asList(ports));
        return input;
    }

    private static PortPreflightPort port(String bindAddress, String protocol) {
        PortPreflightPort port = new PortPreflightPort();
        port.setBindAddress(bindAddress);
        port.setPublicPort(Integer.valueOf(2201));
        port.setPrivatePort(Integer.valueOf(22));
        port.setProtocol(protocol);
        return port;
    }
}
