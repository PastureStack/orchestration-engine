package io.cattle.platform.db.jooq.converter.impl;

import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.util.type.UnmodifiableMap;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonUnmodifiableMap implements UnmodifiableMap<String, Object> {

    private static final Logger log = LoggerFactory.getLogger(JsonUnmodifiableMap.class);

    Map<String, Object> map;
    JsonMapper jsonMapper;
    String text;
    boolean writeable;

    protected JsonUnmodifiableMap(JsonUnmodifiableMap map) {
        this.jsonMapper = map.jsonMapper;
        this.text = map.text;
        if (map.writeable) {
            this.map = map.map;
        }
        this.writeable = true;
    }

    public JsonUnmodifiableMap(JsonMapper mapper, String text) {
        this.jsonMapper = mapper;
        this.text = text;
    }

    @Override
    public int size() {
        return getMap().size();
    }

    @Override
    public boolean isEmpty() {
        return getMap().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return getMap().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return getMap().containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return getMap().get(key);
    }

    @Override
    public Object put(String key, Object value) {
        return getMap().put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return getMap().remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends Object> m) {
        getMap().putAll(m);
    }

    @Override
    public void clear() {
        getMap().clear();
    }

    @Override
    public Set<String> keySet() {
        return getMap().keySet();
    }

    @Override
    public Collection<Object> values() {
        return getMap().values();
    }

    @Override
    public Set<java.util.Map.Entry<String, Object>> entrySet() {
        return getMap().entrySet();
    }

    @Override
    public boolean equals(Object o) {
        return getMap().equals(o);
    }

    @Override
    public int hashCode() {
        return getMap().hashCode();
    }

    @Override
    public String toString() {
        if (this.map == null && isTemplatePlaceholder(this.text)) {
            return this.text;
        }
        return getMap().toString();
    }

    public void setText(String text) {
        this.text = text;
        this.map = null;
        this.writeable = false;
    }

    protected Map<String, Object> getMap() {
        if (this.map == null) {
            if (isTemplatePlaceholder(this.text)) {
                this.map = Collections.unmodifiableMap(new HashMap<String, Object>());
                return this.map;
            }
            try {
                this.map = jsonMapper.readValue(text);
                if (!writeable) {
                    this.map = Collections.unmodifiableMap(this.map);
                }
            } catch (IOException e) {
                log.error("Failed to unmarshall {}", text, e);
                this.map = Collections.unmodifiableMap(new HashMap<String, Object>());
            }
        }
        return this.map;
    }

    protected boolean isTemplatePlaceholder(String text) {
        return text != null && text.length() > 1 && text.charAt(0) == '%' && text.charAt(text.length() - 1) == '%';
    }

    @Override
    public Map<String, Object> getModifiableCopy() {
        return new JsonUnmodifiableMap(this);
    }
}
