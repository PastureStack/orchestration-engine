package io.cattle.platform.iaas.api.port;

final class PortOwner {
    Long portId;
    Long hostId;
    String hostName;
    Long stackId;
    String stackName;
    Long serviceId;
    String serviceName;
    Long instanceId;
    String instanceName;
    String state;
    String bindAddress;
    Integer publicPort;
    Integer privatePort;
    String protocol;
}
