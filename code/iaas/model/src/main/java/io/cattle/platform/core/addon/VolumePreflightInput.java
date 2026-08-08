package io.cattle.platform.core.addon;

import io.github.ibuildthecloud.gdapi.annotation.Field;
import io.github.ibuildthecloud.gdapi.annotation.Type;

import java.util.List;

@Type(list = false)
public class VolumePreflightInput {
    private String volumeDriver;
    private List<String> dataVolumes;
    private Long requestedHostId;
    private Long serviceId;
    private Long instanceId;
    private Long stackId;
    private Boolean global;
    private Integer scale;
    private Integer batchSize;
    private Boolean startFirst;

    @Field(nullable = true)
    public String getVolumeDriver() { return volumeDriver; }
    public void setVolumeDriver(String volumeDriver) { this.volumeDriver = volumeDriver; }

    @Field(required = true)
    public List<String> getDataVolumes() { return dataVolumes; }
    public void setDataVolumes(List<String> dataVolumes) { this.dataVolumes = dataVolumes; }

    @Field(typeString = "reference[host]", nullable = true)
    public Long getRequestedHostId() { return requestedHostId; }
    public void setRequestedHostId(Long requestedHostId) { this.requestedHostId = requestedHostId; }

    @Field(typeString = "reference[service]", nullable = true)
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }

    @Field(typeString = "reference[instance]", nullable = true)
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }

    @Field(typeString = "reference[stack]", nullable = true)
    public Long getStackId() { return stackId; }
    public void setStackId(Long stackId) { this.stackId = stackId; }

    @Field(defaultValue = "false")
    public Boolean getGlobal() { return global; }
    public void setGlobal(Boolean global) { this.global = global; }

    @Field(defaultValue = "1", min = 0)
    public Integer getScale() { return scale; }
    public void setScale(Integer scale) { this.scale = scale; }

    @Field(defaultValue = "1", min = 1)
    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }

    @Field(defaultValue = "false")
    public Boolean getStartFirst() { return startFirst; }
    public void setStartFirst(Boolean startFirst) { this.startFirst = startFirst; }
}
