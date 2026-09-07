package io.cattle.platform.core.addon;

import io.github.ibuildthecloud.gdapi.annotation.Type;
import java.util.List;
import java.util.Map;

@Type(list = false)
public class DeviceRequest {
    private String driver;
    private Integer count;
    private List<String> deviceIds;
    private List<List<String>> capabilities;
    private Map<String, String> options;

    public String getDriver() { return driver; }
    public void setDriver(String driver) { this.driver = driver; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    public List<String> getDeviceIds() { return deviceIds; }
    public void setDeviceIds(List<String> deviceIds) { this.deviceIds = deviceIds; }
    public List<List<String>> getCapabilities() { return capabilities; }
    public void setCapabilities(List<List<String>> capabilities) { this.capabilities = capabilities; }
    public Map<String, String> getOptions() { return options; }
    public void setOptions(Map<String, String> options) { this.options = options; }
}
