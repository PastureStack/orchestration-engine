package io.cattle.platform.iaas.api.volume;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.dao.VolumeDao;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.tables.records.VolumeRecord;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class VolumePreflightServiceTest {

    @Test
    public void parsesAnonymousNamedAndBindVolumePaths() {
        VolumePreflightService.VolumeSpec anonymous = VolumePreflightService.parseSpec("/data", 0);
        VolumePreflightService.VolumeSpec named = VolumePreflightService.parseSpec(
                "database:/var/lib/data", 1);
        VolumePreflightService.VolumeSpec bind = VolumePreflightService.parseSpec(
                "/srv/config:/etc/app:ro", 2);

        assertTrue(anonymous.valid);
        assertEquals(VolumePreflightService.VolumeKind.ANONYMOUS, anonymous.kind);
        assertEquals("/data", anonymous.target);

        assertTrue(named.valid);
        assertEquals(VolumePreflightService.VolumeKind.NAMED, named.kind);
        assertEquals("database", named.source);
        assertEquals("/var/lib/data", named.target);

        assertTrue(bind.valid);
        assertEquals(VolumePreflightService.VolumeKind.BIND, bind.kind);
        assertEquals("/srv/config", bind.source);
        assertEquals("/etc/app", bind.target);
        assertEquals("ro", bind.mode);
    }

    @Test
    public void rejectsInvalidAnonymousModeInsteadOfTreatingItAsABindMount() {
        VolumePreflightService.VolumeSpec spec = VolumePreflightService.parseSpec(
                "/data:execute", 0);

        assertFalse(spec.valid);
        assertEquals(VolumePreflightService.VolumeKind.ANONYMOUS, spec.kind);
        assertTrue(spec.errors.contains("invalid_volume_mode"));
    }

    @Test
    public void acceptsEveryDockerBindPropagationMode() {
        for (String mode : Arrays.asList(
                "private", "rprivate", "shared", "rshared", "slave", "rslave")) {
            VolumePreflightService.VolumeSpec spec = VolumePreflightService.parseSpec(
                    "/var/lib/rancher/volumes:/var/lib/pasturestack/volumes:" + mode, 0);

            assertTrue(mode + " should be accepted for a bind mount", spec.valid);
        }
    }

    @Test
    public void acceptsCombinedAccessAndBindPropagationModes() {
        assertTrue(VolumePreflightService.parseSpec(
                "/srv/data:/data:rw,shared", 0).valid);
        assertTrue(VolumePreflightService.parseSpec(
                "/srv/config:/config:ro,rslave", 0).valid);
    }

    @Test
    public void rejectsBindPropagationForNonBindVolumesAndConflictingPropagation() {
        assertTrue(VolumePreflightService.parseSpec(
                "database:/data:shared", 0).errors.contains("invalid_volume_mode"));
        assertTrue(VolumePreflightService.parseSpec(
                "/data:shared", 0).errors.contains("invalid_volume_mode"));
        assertTrue(VolumePreflightService.parseSpec(
                "/srv/data:/data:shared,rshared", 0).errors.contains("invalid_volume_mode"));
    }

    @Test
    public void rejectsRelativeAndUnsafePaths() {
        assertTrue(VolumePreflightService.parseSpec("data:relative", 0).errors
                .contains("target_path_must_be_absolute"));
        assertTrue(VolumePreflightService.parseSpec("/srv/../secret:/data", 0).errors
                .contains("unsafe_source_path"));
        assertTrue(VolumePreflightService.parseSpec("data:/var//lib/data", 0).errors
                .contains("unsafe_target_path"));
    }

    @Test
    public void rejectsInvalidNamesFormatsAndControlCharacters() {
        assertTrue(VolumePreflightService.parseSpec("../data:/data", 0).errors
                .contains("invalid_volume_name"));
        assertTrue(VolumePreflightService.parseSpec("one:/data:ro:extra", 0).errors
                .contains("invalid_volume_format"));
        assertTrue(VolumePreflightService.parseSpec("/data\u0000", 0).errors
                .contains("volume_path_control_character"));
    }

    @Test
    public void normalizesInputListsWithoutBlankEntries() {
        assertEquals(Arrays.asList("/data", "named:/config"),
                VolumePreflightInputs.stringList(Arrays.asList(
                        " /data ", "", null, "named:/config")));
    }

    @Test
    public void acceptsTheCompletePastureStackNfsContract() {
        assertTrue(VolumePreflightService.nfsContractReasons(
                "pasturestack-nfs", true, "multiHostRW", 2, 2).isEmpty());
    }

    @Test
    public void rejectsEveryIncompletePastureStackNfsContractDimension() {
        assertEquals(Arrays.asList(
                "nfs_requires_environment_scope",
                "nfs_requires_multi_host_rw",
                "nfs_incomplete_host_coverage"),
                VolumePreflightService.nfsContractReasons(
                        "pasturestack-nfs", false, "singleHostRW", 1, 2));
    }

    @Test
    public void doesNotApplyTheNfsContractToOtherStorageDrivers() {
        assertTrue(VolumePreflightService.nfsContractReasons(
                "other-driver", false, "singleHostRW", 0, 2).isEmpty());
    }

    @Test
    public void usesRuntimeResolvableLookupSoPerHostDockerVolumesAreNotAmbiguous() {
        final boolean[] called = new boolean[] { false };
        VolumeDao dao = (VolumeDao) Proxy.newProxyInstance(
                VolumeDao.class.getClassLoader(), new Class<?>[] { VolumeDao.class },
                (proxy, method, args) -> {
                    if ("findSharedOrUnmappedVolumes".equals(method.getName())) {
                        called[0] = true;
                        assertEquals(Long.valueOf(7L), args[0]);
                        assertEquals("rancher-cni-driver", args[1]);
                        return Collections.emptyList();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        VolumePreflightService service = new VolumePreflightService();
        service.volumeDao = dao;

        List<? extends Volume> resolved = service.findResolvableNamedVolumes(
                7L, "rancher-cni-driver");

        assertTrue(called[0]);
        assertTrue(resolved.isEmpty());
        assertTrue(VolumePreflightService.existingNamedVolumeReasons(
                resolved, null).isEmpty());
    }

    @Test
    public void stillRejectsMultipleSharedOrUnmappedVolumes() {
        VolumeRecord first = new VolumeRecord();
        first.setState("active");
        VolumeRecord second = new VolumeRecord();
        second.setState("active");

        assertEquals(Collections.singletonList("ambiguous_existing_volume"),
                VolumePreflightService.existingNamedVolumeReasons(
                        Arrays.asList(first, second), null));
    }

    @Test
    public void stillRejectsDriverMismatchAndUnusableResolvedVolume() {
        VolumeRecord existing = new VolumeRecord();
        existing.setStorageDriverId(Long.valueOf(9L));
        existing.setState("removing");

        assertEquals(Arrays.asList("volume_driver_mismatch", "existing_volume_unusable"),
                VolumePreflightService.existingNamedVolumeReasons(
                        Collections.singletonList(existing), Long.valueOf(10L)));
    }
}
