package io.cattle.platform.iaas.api.port;

import io.cattle.platform.agent.AgentLocator;
import io.cattle.platform.agent.RemoteAgent;
import io.cattle.platform.core.addon.PortPreflightConflict;
import io.cattle.platform.core.addon.PortPreflightInput;
import io.cattle.platform.core.addon.PortPreflightPort;
import io.cattle.platform.core.addon.PortPreflightResult;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.util.PortBindingAddress;
import io.cattle.platform.eventing.EventCallOptions;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.ValidationErrorImpl;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import com.google.common.util.concurrent.ListenableFuture;

public class PortPreflightService {

    public static final String ACTION = "portpreflight";
    public static final String ERROR = "PortConflict";
    public static final String EVENT = "host.port.check";
    private static final long AGENT_TIMEOUT_MILLIS = 1500L;
    private static final long RESULT_TTL_MILLIS = 5000L;

    @Inject
    PortPreflightDao dao;

    @Inject
    ObjectManager objectManager;

    @Inject
    AgentLocator agentLocator;

    public PortPreflightResult check(Account account, PortPreflightInput input) {
        if (account == null || input == null) {
            ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_BODY_CONTENT, "ports");
        }

        validateReferences(account, input);
        String networkMode = normalizeNetworkMode(input.getNetworkMode());
        List<PortPreflightPort> requestedPorts = normalizedPorts(input.getPorts(), networkMode);
        Date checkedAt = new Date();
        PortPreflightResult result = new PortPreflightResult();
        result.setCheckedAt(checkedAt);
        result.setExpiresAt(new Date(checkedAt.getTime() + RESULT_TTL_MILLIS));

        List<Host> environmentHosts = dao.getEligibleHosts(account.getId());
        List<Host> eligibleHosts = eligibleHosts(environmentHosts, input.getRequestedHostId());
        boolean environmentWide = "managed".equals(networkMode);
        List<PortOwner> owners = dao.getPortOwners(account.getId());
        result.setEligibleHostCount(Integer.valueOf(eligibleHosts.size()));

        List<PortPreflightConflict> conflicts = new ArrayList<PortPreflightConflict>();
        Set<Long> activeConflictHosts = new LinkedHashSet<Long>();
        Set<Long> currentServiceReservationHosts = new LinkedHashSet<Long>();
        Set<String> excludedRuntimeContainers = new LinkedHashSet<String>();
        PortPreflightConflict invalidNetworkMapping = invalidNetworkMapping(input.getPorts(), networkMode);
        if (invalidNetworkMapping != null) {
            conflicts.add(invalidNetworkMapping);
            result.setAvailableHostCount(Integer.valueOf(0));
            result.setStatus("blocked");
            result.setConflicts(conflicts);
            result.setInventoryRevision(inventoryRevision(owners, checkedAt));
            return result;
        }

        PortPreflightConflict invalidHostMapping = invalidHostNetworkMapping(input.getPorts(), networkMode);
        if (invalidHostMapping != null) {
            conflicts.add(invalidHostMapping);
            result.setAvailableHostCount(Integer.valueOf(0));
            result.setStatus("blocked");
            result.setConflicts(conflicts);
            result.setInventoryRevision(inventoryRevision(owners, checkedAt));
            return result;
        }

        if (requestedPorts.isEmpty()) {
            result.setAvailableHostCount(Integer.valueOf(eligibleHosts.size()));
            result.setStatus("available");
            result.setConflicts(conflicts);
            result.setInventoryRevision(inventoryRevision(owners, checkedAt));
            return result;
        }

        PortPreflightConflict duplicate = duplicateRequestedPort(requestedPorts);
        if (duplicate != null) {
            conflicts.add(duplicate);
            result.setAvailableHostCount(Integer.valueOf(0));
            result.setStatus("blocked");
            result.setConflicts(conflicts);
            result.setInventoryRevision(inventoryRevision(owners, checkedAt));
            return result;
        }

        for (PortOwner owner : owners) {
            if (!matchesAny(requestedPorts, owner)) {
                continue;
            }
            if (!environmentWide && input.getRequestedHostId() != null
                    && !input.getRequestedHostId().equals(owner.hostId)) {
                continue;
            }
            boolean currentInstance = input.getInstanceId() != null
                    && input.getInstanceId().equals(owner.instanceId);
            boolean currentService = input.getServiceId() != null
                    && input.getServiceId().equals(owner.serviceId);
            if (currentInstance || currentService) {
                if (owner.externalId != null && owner.externalId.trim().length() > 0) {
                    excludedRuntimeContainers.add(owner.externalId.trim());
                }
            }
            if (currentInstance || (currentService && !Boolean.TRUE.equals(input.getStartFirst()))) {
                continue;
            }
            if (currentService && sameMappingAny(requestedPorts, owner)) {
                if (isStoppedOwner(owner.state)) {
                    PortPreflightConflict conflict = fromOwner(owner);
                    conflict.setSeverity("warning");
                    conflict.setReasonCode("stopped_port_owner");
                    conflicts.add(conflict);
                } else {
                    currentServiceReservationHosts.add(owner.hostId);
                }
                continue;
            }

            boolean stopped = isStoppedOwner(owner.state);
            PortPreflightConflict conflict = fromOwner(owner);
            conflict.setSeverity(stopped ? "warning" : "candidate");
            boolean otherHost = input.getRequestedHostId() != null
                    && !input.getRequestedHostId().equals(owner.hostId);
            conflict.setReasonCode(stopped ? "stopped_port_owner"
                    : (environmentWide && otherHost
                            ? "active_port_conflict_on_other_host" : "active_port_conflict"));
            conflicts.add(conflict);
            if (!stopped) {
                activeConflictHosts.add(owner.hostId);
            }
        }

        if (Boolean.TRUE.equals(input.getRuntimeProbe()) && !requestedPorts.isEmpty()) {
            mergeRuntimeConflicts(environmentWide ? environmentHosts : eligibleHosts,
                    requestedPorts, conflicts, activeConflictHosts,
                    excludedRuntimeContainers);
        }

        int availableHosts = 0;
        for (Host host : eligibleHosts) {
            if (!activeConflictHosts.contains(host.getId())
                    && !currentServiceReservationHosts.contains(host.getId())) {
                availableHosts++;
            }
        }
        if (environmentWide && !activeConflictHosts.isEmpty()) {
            availableHosts = 0;
        }
        result.setAvailableHostCount(Integer.valueOf(availableHosts));

        boolean global = Boolean.TRUE.equals(input.getGlobal());
        int scale = Math.max(0, input.getScale() == null ? 1 : input.getScale().intValue());
        int requiredHosts = global ? eligibleHosts.size() : scale;
        if (!global && Boolean.TRUE.equals(input.getStartFirst())) {
            int batchSize = Math.max(1, input.getBatchSize() == null ? 1 : input.getBatchSize().intValue());
            requiredHosts = Math.min(scale, batchSize);
        }
        boolean needsPlacement = global || requiredHosts > 0;
        boolean placementBlocked = needsPlacement && (eligibleHosts.isEmpty()
                || (input.getRequestedHostId() != null && !activeConflictHosts.isEmpty())
                || (global && !activeConflictHosts.isEmpty())
                || availableHosts < requiredHosts);
        boolean blocked = !activeConflictHosts.isEmpty() || placementBlocked;

        boolean unknown = hasSeverity(conflicts, "unknown");
        boolean warning = false;
        for (PortPreflightConflict conflict : conflicts) {
            if ("candidate".equals(conflict.getSeverity())) {
                conflict.setSeverity("blocked");
            }
            warning |= "warning".equals(conflict.getSeverity());
        }

        if (blocked && conflicts.isEmpty()) {
            conflicts.add(capacityConflict(requiredHosts, availableHosts));
        }

        result.setStatus(blocked ? "blocked" : (unknown ? "unknown" : (warning ? "warning" : "available")));
        result.setConflicts(conflicts);
        result.setInventoryRevision(inventoryRevision(owners, checkedAt));
        return result;
    }

    public void assertAvailable(Account account, PortPreflightInput input) {
        PortPreflightResult result = check(account, input);
        if ("blocked".equals(result.getStatus())) {
            String detail = result.getConflicts().isEmpty() ? "port unavailable"
                    : result.getConflicts().get(0).getReasonCode();
            throw new ClientVisibleException(new ValidationErrorImpl(ERROR, "ports",
                    "The requested host port is not available.", detail));
        }
    }

    private static List<Host> eligibleHosts(List<Host> all, Long requestedHostId) {
        if (requestedHostId == null) {
            return all;
        }
        for (Host host : all) {
            if (requestedHostId.equals(host.getId())) {
                return Collections.singletonList(host);
            }
        }
        return Collections.emptyList();
    }

    private void validateReferences(Account account, PortPreflightInput input) {
        if (input.getRequestedHostId() != null) {
            Host host = objectManager.loadResource(Host.class, input.getRequestedHostId());
            validateOwned(host == null ? null : host.getAccountId(), account.getId(), "requestedHostId");
        }
        if (input.getServiceId() != null) {
            Service service = objectManager.loadResource(Service.class, input.getServiceId());
            validateOwned(service == null ? null : service.getAccountId(), account.getId(), "serviceId");
        }
        if (input.getInstanceId() != null) {
            Instance instance = objectManager.loadResource(Instance.class, input.getInstanceId());
            validateOwned(instance == null ? null : instance.getAccountId(), account.getId(), "instanceId");
        }
        if (input.getStackId() != null) {
            Stack stack = objectManager.loadResource(Stack.class, input.getStackId());
            validateOwned(stack == null ? null : stack.getAccountId(), account.getId(), "stackId");
        }
    }

    private static void validateOwned(Long actualAccountId, Long expectedAccountId, String field) {
        if (actualAccountId == null || !actualAccountId.equals(expectedAccountId)) {
            ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_REFERENCE, field);
        }
    }

    public static List<PortPreflightPort> normalizedPorts(List<PortPreflightPort> ports) {
        return normalizedPorts(ports, "managed");
    }

    static List<PortPreflightPort> normalizedPorts(List<PortPreflightPort> ports, String networkMode) {
        if (ports == null) {
            return Collections.emptyList();
        }
        List<PortPreflightPort> result = new ArrayList<PortPreflightPort>();
        for (PortPreflightPort port : ports) {
            if (port == null) {
                continue;
            }
            String portNetworkMode = portNetworkMode(port, networkMode);
            if ("none".equals(portNetworkMode) || portNetworkMode.startsWith("container")) {
                continue;
            }
            Integer effectivePort = "host".equals(portNetworkMode) ? port.getPrivatePort() : port.getPublicPort();
            if (effectivePort == null) {
                continue;
            }
            if (effectivePort.intValue() < 1 || effectivePort.intValue() > 65535) {
                ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_OPTION, "publicPort");
            }
            String protocol = port.getProtocol() == null ? "tcp"
                    : port.getProtocol().trim().toLowerCase(Locale.ENGLISH);
            if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
                ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_OPTION, "protocol");
            }
            PortPreflightPort normalized = new PortPreflightPort();
            normalized.setProtocol(protocol);
            normalized.setPublicPort(effectivePort);
            normalized.setPrivatePort(port.getPrivatePort());
            normalized.setBindAddress("host".equals(portNetworkMode) ? "0.0.0.0"
                    : PortBindingAddress.normalize(port.getBindAddress()));
            normalized.setNetworkMode(portNetworkMode);
            result.add(normalized);
        }
        return result;
    }

    private static String normalizeNetworkMode(String mode) {
        if (mode == null || mode.trim().length() == 0) {
            return "managed";
        }
        return mode.trim().toLowerCase(Locale.ENGLISH);
    }

    private static String portNetworkMode(PortPreflightPort port, String fallbackMode) {
        return normalizeNetworkMode(port == null || port.getNetworkMode() == null
                ? fallbackMode : port.getNetworkMode());
    }

    private static boolean matchesAny(List<PortPreflightPort> ports, PortOwner owner) {
        if (owner == null || owner.publicPort == null || owner.protocol == null) {
            return false;
        }
        for (PortPreflightPort port : ports) {
            if (port.getPublicPort().equals(owner.publicPort)
                    && port.getProtocol().equalsIgnoreCase(owner.protocol)
                    && PortBindingAddress.overlaps(port.getBindAddress(), owner.bindAddress)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameMappingAny(List<PortPreflightPort> ports, PortOwner owner) {
        for (PortPreflightPort port : ports) {
            if (port.getPublicPort().equals(owner.publicPort)
                    && port.getProtocol().equalsIgnoreCase(owner.protocol)
                    && PortBindingAddress.normalize(port.getBindAddress())
                            .equals(PortBindingAddress.normalize(owner.bindAddress))
                    && equalInteger(port.getPrivatePort(), owner.privatePort)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalInteger(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isStoppedOwner(String state) {
        return InstanceConstants.STATE_STOPPED.equals(state)
                || InstanceConstants.STATE_STOPPING.equals(state)
                || InstanceConstants.STATE_CREATED.equals(state)
                || InstanceConstants.STATE_ERROR.equals(state)
                || InstanceConstants.STATE_ERRORING.equals(state);
    }

    private void mergeRuntimeConflicts(List<Host> hosts, List<PortPreflightPort> ports,
            List<PortPreflightConflict> conflicts, Set<Long> activeConflictHosts,
            Set<String> excludedRuntimeContainers) {
        Map<Long, Host> hostById = new LinkedHashMap<Long, Host>();
        Map<Long, ListenableFuture<? extends Event>> futures = new LinkedHashMap<Long, ListenableFuture<? extends Event>>();
        Map<String, Object> data = runtimeRequest(ports);

        for (Host host : hosts) {
            hostById.put(host.getId(), host);
            try {
                RemoteAgent agent = agentLocator.lookupAgent(host);
                if (agent == null) {
                    conflicts.add(unknownConflict(host, "host_unreachable"));
                    continue;
                }
                Event event = EventVO.<Map<String, Object>>newEvent(EVENT)
                        .withResourceType("host")
                        .withResourceId(host.getId().toString())
                        .withData(data);
                futures.put(host.getId(), agent.call(event, new EventCallOptions(0, AGENT_TIMEOUT_MILLIS)));
            } catch (RuntimeException e) {
                conflicts.add(unknownConflict(host, "host_unreachable"));
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AGENT_TIMEOUT_MILLIS);
        for (Map.Entry<Long, ListenableFuture<? extends Event>> entry : futures.entrySet()) {
            Host host = hostById.get(entry.getKey());
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    conflicts.add(unknownConflict(host, "agent_timeout"));
                    continue;
                }
                Event reply = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                mergeRuntimeReply(host, reply, conflicts, activeConflictHosts, excludedRuntimeContainers);
            } catch (Exception e) {
                conflicts.add(unknownConflict(host, "agent_timeout"));
            }
        }
    }

    private static Map<String, Object> runtimeRequest(List<PortPreflightPort> ports) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (PortPreflightPort port : ports) {
            Map<String, Object> value = new HashMap<String, Object>();
            value.put("bindAddress", port.getBindAddress());
            value.put("publicPort", port.getPublicPort());
            value.put("protocol", port.getProtocol());
            values.add(value);
        }
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("ports", values);
        return data;
    }

    private static void mergeRuntimeReply(Host host, Event reply, List<PortPreflightConflict> conflicts,
            Set<Long> activeConflictHosts, Set<String> excludedRuntimeContainers) {
        Map<String, Object> data = asMap(reply == null ? null : reply.getData());
        if (reply == null || Event.TRANSITIONING_ERROR.equals(reply.getTransitioning())
                || !Boolean.TRUE.equals(data.get("supported"))) {
            conflicts.add(unknownConflict(host, "agent_unsupported"));
            return;
        }

        Object raw = data.get("conflicts");
        if (!(raw instanceof List<?>)) {
            if (!Boolean.TRUE.equals(data.get("hostSocketProbeSupported"))) {
                conflicts.add(unknownConflict(host, "agent_unsupported"));
            }
            return;
        }
        for (Object item : (List<?>) raw) {
            Map<String, Object> value = asMap(item);
            if (isExcludedRuntimeContainer(stringValue(value.get("containerId")), excludedRuntimeContainers)) {
                continue;
            }
            Integer publicPort = integerValue(value.get("publicPort"));
            String protocol = stringValue(value.get("protocol"));
            String bindAddress = stringValue(value.get("bindAddress"));
            if (publicPort == null || protocol == null) {
                continue;
            }
            if (alreadyCovered(conflicts, host.getId(), publicPort, protocol, bindAddress)) {
                continue;
            }

            String state = stringValue(value.get("state"));
            boolean stopped = "stopped".equalsIgnoreCase(state) || "exited".equalsIgnoreCase(state);
            PortPreflightConflict conflict = new PortPreflightConflict();
            conflict.setSeverity(stopped ? "warning" : "candidate");
            conflict.setSource("hostProcess".equals(value.get("source")) ? "hostProcess" : "docker");
            conflict.setHostId(host.getId());
            conflict.setHostName(host.getName());
            conflict.setInstanceName(stringValue(value.get("containerName")));
            conflict.setState(state);
            conflict.setBindAddress(PortBindingAddress.normalize(bindAddress));
            conflict.setPublicPort(publicPort);
            conflict.setProtocol(protocol.toLowerCase(Locale.ENGLISH));
            conflict.setReasonCode(stopped ? "stopped_port_owner"
                    : ("hostProcess".equals(conflict.getSource()) ? "host_process_conflict" : "external_docker_conflict"));
            conflicts.add(conflict);
            if (!stopped) {
                activeConflictHosts.add(host.getId());
            }
        }
        if (!Boolean.TRUE.equals(data.get("hostSocketProbeSupported"))) {
            conflicts.add(unknownConflict(host, "agent_unsupported"));
        }
    }

    private static boolean isExcludedRuntimeContainer(String containerId, Set<String> excluded) {
        if (containerId == null || excluded == null || excluded.isEmpty()) {
            return false;
        }
        for (String value : excluded) {
            if (containerId.equals(value)
                    || (containerId.length() >= 12 && value.length() >= 12
                            && (containerId.startsWith(value) || value.startsWith(containerId)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean alreadyCovered(List<PortPreflightConflict> conflicts, Long hostId,
            Integer publicPort, String protocol, String bindAddress) {
        for (PortPreflightConflict conflict : conflicts) {
            if (hostId.equals(conflict.getHostId())
                    && publicPort.equals(conflict.getPublicPort())
                    && protocol.equalsIgnoreCase(conflict.getProtocol())
                    && PortBindingAddress.overlaps(bindAddress, conflict.getBindAddress())) {
                return true;
            }
        }
        return false;
    }

    private static PortPreflightConflict fromOwner(PortOwner owner) {
        PortPreflightConflict conflict = new PortPreflightConflict();
        conflict.setSource("platform");
        conflict.setHostId(owner.hostId);
        conflict.setHostName(owner.hostName);
        conflict.setStackId(owner.stackId);
        conflict.setStackName(owner.stackName);
        conflict.setServiceId(owner.serviceId);
        conflict.setServiceName(owner.serviceName);
        conflict.setInstanceId(owner.instanceId);
        conflict.setInstanceName(owner.instanceName);
        conflict.setState(owner.state);
        conflict.setBindAddress(PortBindingAddress.normalize(owner.bindAddress));
        conflict.setPublicPort(owner.publicPort);
        conflict.setPrivatePort(owner.privatePort);
        conflict.setProtocol(owner.protocol);
        return conflict;
    }

    private static PortPreflightConflict invalidNetworkMapping(List<PortPreflightPort> ports,
            String fallbackMode) {
        if (ports == null) {
            return null;
        }
        for (PortPreflightPort port : ports) {
            if (port == null || port.getPublicPort() == null) {
                continue;
            }
            String networkMode = portNetworkMode(port, fallbackMode);
            if ("none".equals(networkMode) || networkMode.startsWith("container")) {
                return invalidNetworkConflict(port, networkMode);
            }
        }
        return null;
    }

    private static PortPreflightConflict invalidNetworkConflict(PortPreflightPort port, String networkMode) {
        PortPreflightConflict conflict = new PortPreflightConflict();
        conflict.setSeverity("blocked");
        conflict.setSource("platform");
        conflict.setBindAddress(port.getBindAddress());
        conflict.setPublicPort(port.getPublicPort());
        conflict.setPrivatePort(port.getPrivatePort());
        conflict.setProtocol(port.getProtocol());
        conflict.setState(networkMode);
        conflict.setReasonCode("invalid_network_mode_mapping");
        return conflict;
    }

    private static PortPreflightConflict invalidHostNetworkMapping(List<PortPreflightPort> ports,
            String fallbackMode) {
        if (ports == null) {
            return null;
        }
        for (PortPreflightPort port : ports) {
            String networkMode = portNetworkMode(port, fallbackMode);
            if (!"host".equals(networkMode)
                    || port == null || port.getPublicPort() == null || port.getPrivatePort() == null
                    || port.getPublicPort().equals(port.getPrivatePort())) {
                continue;
            }
            PortPreflightConflict conflict = new PortPreflightConflict();
            conflict.setSeverity("blocked");
            conflict.setSource("request");
            conflict.setBindAddress(PortBindingAddress.normalize(port.getBindAddress()));
            conflict.setPublicPort(port.getPublicPort());
            conflict.setPrivatePort(port.getPrivatePort());
            conflict.setProtocol(port.getProtocol() == null ? "tcp" : port.getProtocol().toLowerCase(Locale.ENGLISH));
            conflict.setState("host");
            conflict.setReasonCode("host_network_ignores_published_port");
            return conflict;
        }
        return null;
    }

    private static PortPreflightConflict duplicateRequestedPort(List<PortPreflightPort> ports) {
        for (int left = 0; left < ports.size(); left++) {
            for (int right = left + 1; right < ports.size(); right++) {
                PortPreflightPort a = ports.get(left);
                PortPreflightPort b = ports.get(right);
                if (a.getPublicPort().equals(b.getPublicPort())
                        && a.getProtocol().equals(b.getProtocol())
                        && PortBindingAddress.overlaps(a.getBindAddress(), b.getBindAddress())) {
                    PortPreflightConflict conflict = new PortPreflightConflict();
                    conflict.setSeverity("blocked");
                    conflict.setSource("request");
                    conflict.setBindAddress(a.getBindAddress());
                    conflict.setPublicPort(a.getPublicPort());
                    conflict.setPrivatePort(a.getPrivatePort());
                    conflict.setProtocol(a.getProtocol());
                    conflict.setReasonCode("duplicate_requested_port");
                    return conflict;
                }
            }
        }
        return null;
    }

    private static PortPreflightConflict capacityConflict(int required, int available) {
        PortPreflightConflict conflict = new PortPreflightConflict();
        conflict.setSeverity("blocked");
        conflict.setSource("platform");
        conflict.setState(available + "/" + required);
        conflict.setReasonCode("insufficient_eligible_hosts");
        return conflict;
    }

    private static PortPreflightConflict unknownConflict(Host host, String reason) {
        PortPreflightConflict conflict = new PortPreflightConflict();
        conflict.setSeverity("unknown");
        conflict.setSource("agent");
        conflict.setHostId(host.getId());
        conflict.setHostName(host.getName());
        conflict.setReasonCode(reason);
        return conflict;
    }

    private static boolean hasSeverity(List<PortPreflightConflict> conflicts, String severity) {
        for (PortPreflightConflict conflict : conflicts) {
            if (severity.equals(conflict.getSeverity())) {
                return true;
            }
        }
        return false;
    }

    private static String inventoryRevision(List<PortOwner> owners, Date checkedAt) {
        int hash = 1;
        for (PortOwner owner : owners) {
            hash = 31 * hash + valueHash(owner.hostId);
            hash = 31 * hash + valueHash(owner.instanceId);
            hash = 31 * hash + valueHash(owner.state);
            hash = 31 * hash + valueHash(owner.bindAddress);
            hash = 31 * hash + valueHash(owner.publicPort);
            hash = 31 * hash + valueHash(owner.protocol);
        }
        return Long.toHexString(checkedAt.getTime()) + "-" + Integer.toHexString(hash);
    }

    private static int valueHash(Object value) {
        return value == null ? 0 : value.hashCode();
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String) {
                result.put((String) entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
