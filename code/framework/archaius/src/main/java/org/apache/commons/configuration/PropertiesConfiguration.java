package org.apache.commons.configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

public class PropertiesConfiguration extends MapConfiguration {

    private String fileName;

    public PropertiesConfiguration() {
        super(new Properties());
    }

    public PropertiesConfiguration(File file) throws ConfigurationException {
        this();
        this.fileName = file == null ? null : file.getPath();
        if (file == null) {
            throw new ConfigurationException("Properties file is required", null);
        }
        load(file);
    }

    public PropertiesConfiguration(String fileName) throws ConfigurationException {
        this(new File(fileName));
    }

    public PropertiesConfiguration(URL url) throws ConfigurationException {
        this();
        this.fileName = url == null ? null : url.toString();
        if (url == null) {
            throw new ConfigurationException("Properties URL is required", null);
        }
        load(url);
    }

    public String getFileName() {
        return fileName;
    }

    private void load(File file) throws ConfigurationException {
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            load(input);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load properties file " + file, e);
        } finally {
            close(input);
        }
    }

    private void load(URL url) throws ConfigurationException {
        InputStream input = null;
        try {
            input = url.openStream();
            load(input);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load properties URL " + url, e);
        } finally {
            close(input);
        }
    }

    private void load(InputStream input) throws IOException {
        Properties props = new Properties();
        props.load(input);
        putAll(props);
    }

    private void close(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

}
