package io.cattle.platform.configitem.server.template.impl;

import io.cattle.platform.util.net.UrlUtils;

import java.net.MalformedURLException;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import freemarker.cache.URLTemplateLoader;

public class FreemarkerURLTemplateLoader extends URLTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(FreemarkerTemplateLoader.class);

    @Override
    protected URL getURL(String name) {
        try {
            return UrlUtils.toURL(name);
        } catch (MalformedURLException e) {
            log.error("Bad URL for template [{}]", name, e);
            return null;
        }
    }

}
