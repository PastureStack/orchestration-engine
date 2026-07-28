package io.cattle.platform.servicediscovery.deployment.impl.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class DeploymentUnitStringBoundaryTest {

    @Test
    public void namedVolumesReturnsOnlyNamedVolumeReferences() {
        assertEquals(Arrays.asList("data:/var/lib/data", "cache:/cache"),
                DeploymentUnit.namedVolumes(Arrays.asList("data:/var/lib/data", "/host:/container", "cache:/cache")));
    }

    @Test
    public void namedVolumesReturnsEmptyListWhenMissing() {
        assertTrue(DeploymentUnit.namedVolumes(null).isEmpty());
    }

    @Test(expected = ClassCastException.class)
    public void namedVolumesRejectsNonStringVolumeReferences() {
        DeploymentUnit.namedVolumes(Arrays.asList("data:/var/lib/data", Integer.valueOf(42)));
    }

    @Test
    public void isNamedVolumeNameRequiresContainerPathAndNonPathName() {
        assertTrue(DeploymentUnit.isNamedVolumeName("data:/var/lib/data"));
        assertFalse(DeploymentUnit.isNamedVolumeName("data"));
        assertFalse(DeploymentUnit.isNamedVolumeName("/host:/container"));
    }

    @Test
    public void sidekickConfigNamesReturnsEmptyListWhenMissing() {
        assertTrue(DeploymentUnit.sidekickConfigNames(null, DeploymentUnit.SidekickType.DATA).isEmpty());
    }

    @Test
    public void sidekickConfigNamesCopiesListValues() {
        assertEquals(Arrays.asList("sidekick-a", "sidekick-b"),
                DeploymentUnit.sidekickConfigNames(Arrays.asList("sidekick-a", "sidekick-b"),
                        DeploymentUnit.SidekickType.DATA));
    }

    @Test
    public void sidekickConfigNamesPreservesScalarToStringBehavior() {
        assertEquals(Arrays.asList("123"),
                DeploymentUnit.sidekickConfigNames(Integer.valueOf(123), DeploymentUnit.SidekickType.NETWORK));
    }

    @Test(expected = ClassCastException.class)
    public void sidekickConfigNamesRejectsNonStringListValues() {
        DeploymentUnit.sidekickConfigNames(Arrays.asList("sidekick-a", Boolean.TRUE), DeploymentUnit.SidekickType.DATA);
    }
}
