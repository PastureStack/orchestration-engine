package io.cattle.platform.core.addon;

import io.github.ibuildthecloud.gdapi.annotation.Field;
import io.github.ibuildthecloud.gdapi.annotation.Type;

@Type(list = false)
public class PortPreflightConflict {
    private String severity;
    private String source;
    private Long hostId;
    private String hostName;
    private Long stackId;
    private String stackName;
    private Long serviceId;
    private String serviceName;
    private Long instanceId;
    private String instanceName;
    private String state;
    private String bindAddress;
    private Integer publicPort;
    private Integer privatePort;
    private String protocol;
    private String reasonCode;

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    @Field(typeString = "reference[host]", nullable = true)
    public Long getHostId() { return hostId; }
    public void setHostId(Long hostId) { this.hostId = hostId; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    @Field(typeString = "reference[stack]", nullable = true)
    public Long getStackId() { return stackId; }
    public void setStackId(Long stackId) { this.stackId = stackId; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    @Field(typeString = "reference[service]", nullable = true)
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    @Field(typeString = "reference[instance]", nullable = true)
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    public Integer getPublicPort() { return publicPort; }
    public void setPublicPort(Integer publicPort) { this.publicPort = publicPort; }
    public Integer getPrivatePort() { return privatePort; }
    public void setPrivatePort(Integer privatePort) { this.privatePort = privatePort; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
}
