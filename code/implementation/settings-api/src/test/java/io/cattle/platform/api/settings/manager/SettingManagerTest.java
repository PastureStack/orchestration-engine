package io.cattle.platform.api.settings.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.settings.model.ActiveSetting;
import io.cattle.platform.archaius.util.ConfigurationView;
import io.cattle.platform.core.model.Setting;
import io.cattle.platform.core.model.tables.records.SettingRecord;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.url.NullUrlBuilder;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SettingManagerTest {

    @Test
    public void settingsUseConfigurationViewWithoutArchaiusConcreteType() {
        TestSettingManager manager = new TestSettingManager();
        SettingRecord dbSetting = new SettingRecord(1L, "db.setting", "db-value");
        FixedConfigurationView config = new FixedConfigurationView()
                .put("db.setting", "active-db-value", "DatabaseConfig")
                .put("new.setting", "active-new-value", "DefaultsConfig")
                .put("SECRET_ENV", "secret", "Environment Variables");

        Map<String, ActiveSetting> settings = byName(manager.getSettingsForTest(Arrays.<Setting>asList(dbSetting), config));

        assertTrue(settings.containsKey("db.setting"));
        assertTrue(settings.containsKey("new.setting"));
        assertFalse(settings.containsKey("SECRET_ENV"));

        ActiveSetting db = settings.get("db.setting");
        assertEquals("db-value", db.getValue());
        assertEquals("active-db-value", db.getActiveValue());
        assertEquals("DatabaseConfig", db.getSource());
        assertSame(dbSetting, db.getSetting());

        ActiveSetting generated = settings.get("new.setting");
        assertEquals("active-new-value", generated.getActiveValue());
        assertEquals("DefaultsConfig", generated.getSource());
    }

    @Test
    public void settingListUsesCheckedSettingCasts() {
        TestSettingManager manager = new TestSettingManager();
        SettingRecord dbSetting = new SettingRecord(1L, "db.setting", "db-value");

        List<Setting> result = manager.settingList(Arrays.<Object>asList(dbSetting));

        assertEquals(1, result.size());
        assertSame(dbSetting, result.get(0));
    }

    @Test
    public void namedIdentityMatchesRenderedSettingNameWithAndWithoutDatabaseRow() {
        ApiRequest request = new ApiRequest(null, new SchemaFactoryImpl());
        request.setMethod("GET");
        request.setUrlBuilder(new NullUrlBuilder());
        ApiContext.newContext().setApiRequest(request);
        try {
            ActiveSetting configured = new ActiveSetting("named.setting", "active-value", "DefaultsConfig");
            ActiveSetting database = new ActiveSetting("ignored", "active-value", "DatabaseConfig");
            database.setSetting(new SettingRecord(42L, "db.named.setting", "db-value"));
            assertEquals("42", database.getId());
            for (ActiveSetting value : Arrays.asList(configured, database)) {
                Resource rendered = new IdentityRenderingSettingManager().render(value, request);
                assertEquals("setting", value.getApiResourceType());
                assertEquals(value.getName(), value.getApiResourceId());
                assertEquals(value.getApiResourceId(), rendered.getId());
                assertEquals("activeSetting", rendered.getType());
                assertEquals("setting", rendered.getFields().get("baseType"));
            }
        } finally {
            ApiContext.remove();
        }
    }

    private static class IdentityRenderingSettingManager extends SettingManager {
        Resource render(ActiveSetting value, ApiRequest request) {
            return createResource(value, ApiContext.getContext().getIdFormatter(), request);
        }
        @Override protected Schema getSchemaForDisplay(SchemaFactory factory, Object value) {
            SchemaImpl schema = new SchemaImpl();
            schema.setId("activeSetting");
            return schema;
        }
        @Override protected Resource constructResource(IdFormatter formatter, SchemaFactory factory, Schema schema, Object value, ApiRequest request) {
            ActiveSetting setting = (ActiveSetting) value;
            return new ResourceImpl(setting.getId(), "activeSetting", new LinkedHashMap<>(Map.of("name", setting.getName())));
        }
        @Override protected void addLinks(Object obj, SchemaFactory factory, Schema schema, Resource resource) { }
        @Override protected void addActions(Object obj, SchemaFactory factory, Schema schema, Resource resource) { }
    }

    private Map<String, ActiveSetting> byName(List<ActiveSetting> settings) {
        Map<String, ActiveSetting> result = new LinkedHashMap<String, ActiveSetting>();
        for (ActiveSetting setting : settings) {
            result.put(setting.getName(), setting);
        }
        return result;
    }

    private static class TestSettingManager extends SettingManager {

        List<ActiveSetting> getSettingsForTest(List<Setting> settings, ConfigurationView config) {
            return getSettings(settings, config);
        }

    }

    private static class FixedConfigurationView implements ConfigurationView {

        private final Map<String, Object> values = new LinkedHashMap<String, Object>();
        private final Map<String, String> sources = new LinkedHashMap<String, String>();

        FixedConfigurationView put(String name, Object value, String source) {
            values.put(name, value);
            sources.put(name, source);
            return this;
        }

        @Override
        public Iterator<String> getKeys() {
            return values.keySet().iterator();
        }

        @Override
        public Object getProperty(String name) {
            return values.get(name);
        }

        @Override
        public String getSourceName(String name) {
            return sources.get(name);
        }

    }

}
