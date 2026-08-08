package io.cattle.platform.agent.server.resource.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.codec.binary.Hex;

public class AgentResources {

    Map<String, Map<String, Object>> hosts = new TreeMap<>();
    Map<String, Map<String, Object>> storagePools = new TreeMap<>();
    Map<String, Map<String, Object>> ipAddresses = new TreeMap<>();
    String hash = null;

    public String getHash() {
        if (hash != null) {
            return hash;
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to find SHA-256 digest", e);
        }

        hashMap(md, hosts);
        hashMap(md, storagePools);
        hashMap(md, ipAddresses);

        return Hex.encodeHexString(md.digest());
    }

    public boolean hasContent() {
        return hosts.size() > 0;
    }

    protected void hashMap(MessageDigest md, Map<String, Map<String, Object>> data) {
        for (Map<String, Object> value : data.values()) {
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                md.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                Object obj = entry.getValue();
                if (obj != null) {
                    md.update(obj.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    public void setHost(String uuid, Map<String, Object> data) {
        hosts.put(uuid, new TreeMap<>(data));
    }

    public Map<String, Map<String, Object>> getHosts() {
        return hosts;
    }

    public Map<String, Map<String, Object>> getStoragePools() {
        return storagePools;
    }

    public void setStoragePool(String uuid, Map<String, Object> data) {
        storagePools.put(uuid, new TreeMap<>(data));
    }

    public Map<String, Map<String, Object>> getIpAddresses() {
        return ipAddresses;
    }

    public void setIpAddress(String uuid, Map<String, Object> data) {
        ipAddresses.put(uuid, new TreeMap<>(data));
    }
}
