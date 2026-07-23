package io.cattle.platform.archaius.sources;

import org.apache.commons.configuration.AbstractConfiguration;

public interface RegisteredConfigSource {

    AbstractConfiguration asConfiguration();

}
