package io.cattle.platform.core.addon;

import io.github.ibuildthecloud.gdapi.annotation.Type;

import java.util.Date;
import java.util.List;

@Type(list = false)
public class PortPreflightResult {
    private String status;
    private Date checkedAt;
    private Date expiresAt;
    private String inventoryRevision;
    private Integer eligibleHostCount;
    private Integer availableHostCount;
    private List<PortPreflightConflict> conflicts;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Date checkedAt) { this.checkedAt = checkedAt; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public String getInventoryRevision() { return inventoryRevision; }
    public void setInventoryRevision(String inventoryRevision) { this.inventoryRevision = inventoryRevision; }
    public Integer getEligibleHostCount() { return eligibleHostCount; }
    public void setEligibleHostCount(Integer eligibleHostCount) { this.eligibleHostCount = eligibleHostCount; }
    public Integer getAvailableHostCount() { return availableHostCount; }
    public void setAvailableHostCount(Integer availableHostCount) { this.availableHostCount = availableHostCount; }
    public List<PortPreflightConflict> getConflicts() { return conflicts; }
    public void setConflicts(List<PortPreflightConflict> conflicts) { this.conflicts = conflicts; }
}
