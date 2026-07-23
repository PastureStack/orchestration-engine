package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.github.ibuildthecloud.gdapi.exception.ValidationErrorException;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class SelectorServiceCreateValidationFilterTest {

    @Test
    public void launchConfigReturnsNullWhenMissing() {
        assertNull(SelectorServiceCreateValidationFilter.launchConfig(null));
    }

    @Test
    public void launchConfigPreservesMapInstance() {
        Map<Object, Object> launchConfig = new LinkedHashMap<>();
        launchConfig.put(Integer.valueOf(7), "legacy-non-string-key");

        assertSame(launchConfig, SelectorServiceCreateValidationFilter.launchConfig(launchConfig));
    }

    @Test(expected = ClassCastException.class)
    public void launchConfigRejectsNonMapValues() {
        SelectorServiceCreateValidationFilter.launchConfig("not-a-map");
    }

    @Test(expected = ValidationErrorException.class)
    public void validateImageRequiresImageWhenNotSelectorOnly() {
        SelectorServiceCreateValidationFilter.validateImageData(new LinkedHashMap<>(), false);
    }

    @Test
    public void validateImageAllowsSelectorOnlyServiceWithoutImage() {
        SelectorServiceCreateValidationFilter.validateImageData(new LinkedHashMap<>(), true);
    }

    @Test(expected = ValidationErrorException.class)
    public void validateImageRejectsNoneImageWhenNotSelectorOnly() {
        Map<String, Object> launchConfig = new LinkedHashMap<>();
        launchConfig.put(InstanceConstants.FIELD_IMAGE_UUID, ServiceConstants.IMAGE_NONE);

        SelectorServiceCreateValidationFilter.validateImageData(launchConfig, false);
    }
}
