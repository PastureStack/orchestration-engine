package io.cattle.platform.configitem.freemarker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import freemarker.template.TemplateException;

/* The only purpose of this class is to narrow the return type of
 * getSettings() so that Spring can instantiate this bean.
 */
public class Configuration extends freemarker.template.Configuration {

    private final Properties settings = new Properties();

    public Configuration() {
        super(freemarker.template.Configuration.VERSION_2_3_0);
    }

    @Override
    public void setSetting(String name, String value) throws TemplateException {
        super.setSetting(name, value);
        settings.setProperty(name, value);
    }

    @Override
    public void setSettings(Properties props) throws TemplateException {
        super.setSettings(props);
        settings.putAll(props);
    }

    @Override
    public void setSettings(InputStream propsIn) throws TemplateException, IOException {
        Properties props = new Properties();
        props.load(propsIn);
        setSettings(props);
    }

    // Spring bean creation still requires the narrowed Properties return type.
    @SuppressWarnings("deprecation")
    @Override
    public Properties getSettings() {
        Properties props = new Properties();
        props.putAll(settings);
        return props;
    }

}
