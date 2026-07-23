package io.cattle.platform.configitem.server.agentinclude.impl;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.configitem.server.agentinclude.AgentIncludeMap;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.codec.binary.Hex;

public class AgentIncludeMapImpl implements AgentIncludeMap {

    private static final ConfigListProperty<String> KEYS = ArchaiusUtil.getStringListProperty("agent.packages.types");

    Map<String, ConfigListProperty<String>> packages = new ConcurrentHashMap<String, ConfigListProperty<String>>();
    Map<String, ConfigProperty<String>> values = new ConcurrentHashMap<String, ConfigProperty<String>>();

    @Override
    public List<String> getNamedMaps() {
        return KEYS.get();
    }

    protected String sanitize(String s) {
        if (s == null) {
            return s;
        }

        return s.replaceAll("-", ".").toLowerCase();
    }

    @Override
    public Map<String, String> getMap(String name) {
        Map<String, String> result = new LinkedHashMap<String, String>();

        if (name == null) {
            return result;
        }

        String packageKey = "agent.packages." + sanitize(name);
        ConfigListProperty<String> packageProperty = packages.get(packageKey);

        if (packageProperty == null) {
            packageProperty = ArchaiusUtil.getStringListProperty(packageKey);
            packages.put(packageKey, packageProperty);
        }

        for (String item : packageProperty.get()) {
            String key = String.format("agent.package.%s.url", sanitize(item));
            ConfigProperty<String> prop = values.get(key);

            if (prop == null) {
                prop = ArchaiusUtil.getStringProperty(key);
                values.put(key, prop);
            }

            String value = prop.get();

            if (value != null) {
                result.put(item, value);
            }
        }

        return result;
    }

    @Override
    public String getSourceRevision(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");

            for (Map.Entry<String, String> entry : getMap(name).entrySet()) {
                md.update(entry.getKey().getBytes("UTF-8"));
                md.update(entry.getValue().getBytes("UTF-8"));
            }

            return Hex.encodeHexString(md.digest());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

}