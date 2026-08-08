package io.cattle.platform.core.addon;

import io.github.ibuildthecloud.gdapi.annotation.Type;

import java.util.Date;
import java.util.List;

@Type(list = false)
public class VolumePreflightResult {
    private String status;
    private Date checkedAt;
    private Date expiresAt;
    private String inventoryRevision;
    private String driverName;
    private String driverState;
    private String driverScope;
    private String volumeAccessMode;
    private Integer eligibleHostCount;
    private Integer availableHostCount;
    private List<VolumePreflightIssue> issues;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Date checkedAt) { this.checkedAt = checkedAt; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public String getInventoryRevision() { return inventoryRevision; }
    public void setInventoryRevision(String inventoryRevision) { this.inventoryRevision = inventoryRevision; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getDriverState() { return driverState; }
    public void setDriverState(String driverState) { this.driverState = driverState; }
    public String getDriverScope() { return driverScope; }
    public void setDriverScope(String driverScope) { this.driverScope = driverScope; }
    public String getVolumeAccessMode() { return volumeAccessMode; }
    public void setVolumeAccessMode(String volumeAccessMode) { this.volumeAccessMode = volumeAccessMode; }
    public Integer getEligibleHostCount() { return eligibleHostCount; }
    public void setEligibleHostCount(Integer eligibleHostCount) { this.eligibleHostCount = eligibleHostCount; }
    public Integer getAvailableHostCount() { return availableHostCount; }
    public void setAvailableHostCount(Integer availableHostCount) { this.availableHostCount = availableHostCount; }
    public List<VolumePreflightIssue> getIssues() { return issues; }
    public void setIssues(List<VolumePreflightIssue> issues) { this.issues = issues; }
}
