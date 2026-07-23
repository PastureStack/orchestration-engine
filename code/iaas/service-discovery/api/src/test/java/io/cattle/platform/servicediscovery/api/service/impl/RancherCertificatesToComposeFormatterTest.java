package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class RancherCertificatesToComposeFormatterTest {

    @Test
    public void certificateIdsPreservesNumberValues() {
        assertEquals(Arrays.asList(Integer.valueOf(1), Long.valueOf(2L), null),
                RancherCertificatesToComposeFormatter.certificateIds(
                        Arrays.asList(Integer.valueOf(1), Long.valueOf(2L), null)));
    }

    @Test(expected = ClassCastException.class)
    public void certificateIdsRejectsNonListValues() {
        RancherCertificatesToComposeFormatter.certificateIds(Integer.valueOf(1));
    }

    @Test(expected = ClassCastException.class)
    public void certificateIdsRejectsNonNumberElements() {
        RancherCertificatesToComposeFormatter.certificateIds(Arrays.asList(Integer.valueOf(1), "2"));
    }

    @Test(expected = NullPointerException.class)
    public void certificateIdsPreservesNullListFailureMode() {
        RancherCertificatesToComposeFormatter.certificateIds(null);
    }
}
