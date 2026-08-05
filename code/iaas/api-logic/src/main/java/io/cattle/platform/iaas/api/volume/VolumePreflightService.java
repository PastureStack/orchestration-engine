package io.cattle.platform.iaas.api.volume;

import static io.cattle.platform.core.model.tables.StorageDriverTable.STORAGE_DRIVER;
import static io.cattle.platform.core.model.tables.VolumeTable.VOLUME;

import io.cattle.platform.core.addon.VolumePreflightInput;
import io.cattle.platform.core.addon.VolumePreflightIssue;
import io.cattle.platform.core.addon.VolumePreflightResult;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.StorageDriverConstants;
import io.cattle.platform.core.dao.StoragePoolDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.StorageDriver;
import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.iaas.api.port.PortPreflightDao;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.ValidationErrorImpl;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

public class VolumePreflightService {

    public static final String ACTION = "volumepreflight";
    public static final String ERROR = "VolumeUnavailable";
    private static final long RESULT_TTL_MILLIS = 5000L;
    private static final int MAX_SPEC_LENGTH = 4096;
    private static final Pattern VOLUME_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._@-]*$");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");
    private static final Set<String> ALLOWED_MODES = new HashSet<String>(Arrays.asList(
            "ro", "rw", "z", "Z", "nocopy"));
    private static final Set<String> UNUSABLE_VOLUME_STATES = new HashSet<String>(Arrays.asList(
            "removing", "removed", "purging", "purged", "error", "erroring"));

    @Inject
    ObjectManager objectManager;

    @Inject
    StoragePoolDao storagePoolDao;

    @Inject
    PortPreflightDao hostDao;

    public VolumePreflightResult check(Account account, VolumePreflightInput input) {
        if (account == null || input == null || input.getDataVolumes() == null) {
            ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_BODY_CONTENT, "dataVolumes");
        }

        validateReferences(account, input);
        Date checkedAt = new Date();
        VolumePreflightResult result = new VolumePreflightResult();
        List<VolumePreflightIssue> issues = new ArrayList<VolumePreflightIssue>();
        List<VolumeSpec> specs = parseSpecs(input.getDataVolumes(), issues);
        List<Host> environmentHosts = hostDao.getEligibleHosts(account.getId());
        List<Host> placementHosts = placementHosts(environmentHosts, input.getRequestedHostId());
        String requestedDriver = normalizeDriver(input.getVolumeDriver());
        StorageDriver driver = null;
        int availableHosts = placementHosts.size();

        result.setCheckedAt(checkedAt);
        result.setExpiresAt(new Date(checkedAt.getTime() + RESULT_TTL_MILLIS));
        result.setEligibleHostCount(Integer.valueOf(placementHosts.size()));
        result.setDriverName(requestedDriver.length() == 0 ? "local" : requestedDriver);

        if (requestedDriver.length() > 0) {
            List<? extends StorageDriver> matches = objectManager.find(StorageDriver.class,
                    STORAGE_DRIVER.ACCOUNT_ID, account.getId(),
                    STORAGE_DRIVER.REMOVED, null,
                    STORAGE_DRIVER.NAME, requestedDriver);
            if (matches.size() != 1) {
                issues.add(issue("blocked", matches.isEmpty()
                        ? "driver_not_found" : "duplicate_driver_name", null, null, requestedDriver));
            } else {
                driver = matches.get(0);
                result.setDriverState(driver.getState());
                String scope = normalizedScope(driver);
                String accessMode = DataAccessor.fieldString(driver,
                        StorageDriverConstants.FIELD_VOLUME_ACCESS_MODE);
                result.setDriverScope(scope);
                result.setVolumeAccessMode(accessMode);

                if (!CommonStatesConstants.ACTIVE.equals(driver.getState())) {
                    issues.add(issue("blocked", "driver_inactive", null, null, requestedDriver));
                }
                List<String> capabilities = DataAccessor.fieldStringList(driver,
                        StorageDriverConstants.FIELD_VOLUME_CAPABILITES);
                if (capabilities != null && capabilities.contains(StorageDriverConstants.CAPABILITY_SECRETS)) {
                    issues.add(issue("blocked", "reserved_secrets_driver", null, null, requestedDriver));
                }

                availableHosts = validateDriverCoverage(account, input, driver, scope,
                        accessMode, environmentHosts, placementHosts, issues);
            }
        }

        validateSpecsAgainstDriver(account, specs, driver, requestedDriver, issues);

        result.setAvailableHostCount(Integer.valueOf(Math.max(0, availableHosts)));
        result.setIssues(issues);
        result.setStatus(overallStatus(issues));
        result.setInventoryRevision(inventoryRevision(driver, environmentHosts, availableHosts, specs));
        return result;
    }

    public void assertAvailable(Account account, VolumePreflightInput input) {
        VolumePreflightResult result = check(account, input);
        if ("blocked".equals(result.getStatus())) {
            String detail = result.getIssues().isEmpty() ? "volume unavailable"
                    : result.getIssues().get(0).getReasonCode();
            throw new ClientVisibleException(new ValidationErrorImpl(ERROR, "dataVolumes",
                    "The requested volume configuration is not available.", detail));
        }
    }

    private int validateDriverCoverage(Account account, VolumePreflightInput input,
            StorageDriver driver, String scope, String accessMode, List<Host> environmentHosts,
            List<Host> placementHosts, List<VolumePreflightIssue> issues) {
        List<? extends StoragePool> pools = storagePoolDao.findNonRemovedStoragePoolByDriver(driver.getId());
        Set<Long> activePoolIds = new LinkedHashSet<Long>();
        for (StoragePool pool : pools) {
            if (CommonStatesConstants.ACTIVE.equals(pool.getState())
                    || CommonStatesConstants.ACTIVATING.equals(pool.getState())
                    || CommonStatesConstants.UPDATING_ACTIVE.equals(pool.getState())) {
                activePoolIds.add(pool.getId());
            }
        }
        if (activePoolIds.isEmpty()) {
            issues.add(issue("blocked", "no_active_storage_pool", null, null, driver.getName()));
            return 0;
        }

        Map<Long, Long> mappedPools = storagePoolDao.findStoragePoolHostsByDriver(
                account.getId(), driver.getId());
        Set<Long> coveredHosts = new LinkedHashSet<Long>();
        for (Map.Entry<Long, Long> entry : mappedPools.entrySet()) {
            if (entry.getValue() != null && activePoolIds.contains(entry.getValue())) {
                coveredHosts.add(entry.getKey());
            }
        }

        boolean environmentScope = StorageDriverConstants.SCOPE_ENVIRONMENT.equals(scope);
        List<Host> requiredHosts = environmentScope || Boolean.TRUE.equals(input.getGlobal())
                ? environmentHosts : placementHosts;
        int available = 0;
        for (Host host : requiredHosts) {
            if (coveredHosts.contains(host.getId())) {
                available++;
            } else {
                VolumePreflightIssue missing = issue("blocked", "host_pool_missing",
                        null, null, driver.getName());
                missing.setHostId(host.getId());
                missing.setHostName(host.getName());
                issues.add(missing);
            }
        }

        if (requiredHosts.isEmpty()) {
            issues.add(issue("blocked", "no_eligible_hosts", null, null, driver.getName()));
        }

        for (String reason : nfsContractReasons(driver.getName(), environmentScope,
                accessMode, available, environmentHosts.size())) {
            issues.add(issue("blocked", reason, null, null, driver.getName()));
        }

        return available;
    }

    static List<String> nfsContractReasons(String driverName, boolean environmentScope,
            String accessMode, int availableHosts, int environmentHostCount) {
        if (!"pasturestack-nfs".equals(driverName)) {
            return Collections.emptyList();
        }

        List<String> reasons = new ArrayList<String>();
        if (!environmentScope) {
            reasons.add("nfs_requires_environment_scope");
        }
        if (!"multiHostRW".equals(accessMode)) {
            reasons.add("nfs_requires_multi_host_rw");
        }
        if (availableHosts != environmentHostCount) {
            reasons.add("nfs_incomplete_host_coverage");
        }
        return reasons;
    }

    private void validateSpecsAgainstDriver(Account account, List<VolumeSpec> specs,
            StorageDriver driver, String requestedDriver, List<VolumePreflightIssue> issues) {
        Map<String, Integer> targets = new LinkedHashMap<String, Integer>();
        Long expectedDriverId = driver == null ? null : driver.getId();

        for (VolumeSpec spec : specs) {
            if (!spec.valid) {
                continue;
            }

            Integer previous = targets.put(spec.target, Integer.valueOf(spec.index));
            if (previous != null) {
                issues.add(issue("blocked", "duplicate_target_path", Integer.valueOf(spec.index),
                        spec.raw, requestedDriver));
            }

            if (requestedDriver.length() > 0 && spec.kind == VolumeKind.ANONYMOUS) {
                issues.add(issue("warning", "anonymous_volume_with_driver",
                        Integer.valueOf(spec.index), spec.raw, requestedDriver));
            }
            if (requestedDriver.length() > 0 && spec.kind == VolumeKind.BIND) {
                issues.add(issue("warning", "bind_mount_ignores_driver",
                        Integer.valueOf(spec.index), spec.raw, requestedDriver));
            }
            if (spec.kind != VolumeKind.NAMED) {
                continue;
            }

            List<? extends Volume> volumes = objectManager.find(Volume.class,
                    VOLUME.ACCOUNT_ID, account.getId(),
                    VOLUME.NAME, spec.source,
                    VOLUME.REMOVED, null);
            if (volumes.size() > 1) {
                issues.add(issue("blocked", "ambiguous_existing_volume",
                        Integer.valueOf(spec.index), spec.raw, requestedDriver));
                continue;
            }
            if (volumes.size() == 1) {
                Volume existing = volumes.get(0);
                if (!equalLong(expectedDriverId, existing.getStorageDriverId())) {
                    issues.add(issue("blocked", "volume_driver_mismatch",
                            Integer.valueOf(spec.index), spec.raw, requestedDriver));
                }
                if (UNUSABLE_VOLUME_STATES.contains(String.valueOf(existing.getState()).toLowerCase(Locale.ENGLISH))) {
                    issues.add(issue("blocked", "existing_volume_unusable",
                            Integer.valueOf(spec.index), spec.raw, requestedDriver));
                }
            }
        }
    }

    static List<VolumeSpec> parseSpecs(List<String> values, List<VolumePreflightIssue> issues) {
        List<VolumeSpec> result = new ArrayList<VolumeSpec>();
        for (int index = 0; index < values.size(); index++) {
            String raw = values.get(index);
            VolumeSpec spec = parseSpec(raw, index);
            result.add(spec);
            for (String error : spec.errors) {
                issues.add(issue("blocked", error, Integer.valueOf(index), spec.raw, null));
            }
        }
        return result;
    }

    static VolumeSpec parseSpec(String input, int index) {
        VolumeSpec spec = new VolumeSpec();
        spec.index = index;
        spec.raw = input == null ? "" : input.trim();
        if (spec.raw.length() == 0) {
            spec.errors.add("volume_path_required");
            return spec;
        }
        if (spec.raw.length() > MAX_SPEC_LENGTH) {
            spec.errors.add("volume_path_too_long");
        }
        if (input != null && CONTROL.matcher(input).find()) {
            spec.errors.add("volume_path_control_character");
        }

        String[] parts = spec.raw.split(":", -1);
        if (parts.length > 3) {
            spec.errors.add("invalid_volume_format");
            return spec;
        }
        if (parts.length == 1) {
            spec.kind = VolumeKind.ANONYMOUS;
            spec.target = parts[0];
        } else if (parts.length == 2) {
            if (parts[0].startsWith("/") && !parts[1].startsWith("/")) {
                spec.kind = VolumeKind.ANONYMOUS;
                spec.target = parts[0];
                spec.mode = parts[1];
            } else {
                spec.source = parts[0];
                spec.target = parts[1];
                spec.kind = spec.source.startsWith("/") ? VolumeKind.BIND : VolumeKind.NAMED;
            }
        } else {
            spec.source = parts[0];
            spec.target = parts[1];
            spec.mode = parts[2];
            spec.kind = spec.source.startsWith("/") ? VolumeKind.BIND : VolumeKind.NAMED;
        }

        if (spec.target == null || !spec.target.startsWith("/")) {
            spec.errors.add("target_path_must_be_absolute");
        } else if (unsafePath(spec.target)) {
            spec.errors.add("unsafe_target_path");
        }
        if (spec.kind == VolumeKind.NAMED
                && (spec.source == null || !VOLUME_NAME.matcher(spec.source).matches())) {
            spec.errors.add("invalid_volume_name");
        }
        if (spec.kind == VolumeKind.BIND) {
            if (spec.source == null || !spec.source.startsWith("/")) {
                spec.errors.add("source_path_must_be_absolute");
            } else if (unsafePath(spec.source)) {
                spec.errors.add("unsafe_source_path");
            }
        }
        if (spec.mode != null && !validMode(spec.mode)) {
            spec.errors.add("invalid_volume_mode");
        }
        spec.valid = spec.errors.isEmpty();
        return spec;
    }

    private static boolean validMode(String mode) {
        if (mode.length() == 0) {
            return false;
        }
        Set<String> seen = new HashSet<String>();
        for (String value : mode.split(",", -1)) {
            if (!ALLOWED_MODES.contains(value) || !seen.add(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean unsafePath(String path) {
        if (path.contains("//")) {
            return true;
        }
        for (String part : path.split("/", -1)) {
            if (".".equals(part) || "..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    private void validateReferences(Account account, VolumePreflightInput input) {
        if (input.getRequestedHostId() != null) {
            Host host = objectManager.loadResource(Host.class, input.getRequestedHostId());
            validateOwned(host == null ? null : host.getAccountId(), account.getId(), "requestedHostId");
        }
        if (input.getServiceId() != null) {
            Service service = objectManager.loadResource(Service.class, input.getServiceId());
            validateOwned(service == null ? null : service.getAccountId(), account.getId(), "serviceId");
        }
        if (input.getInstanceId() != null) {
            Instance instance = objectManager.loadResource(Instance.class, input.getInstanceId());
            validateOwned(instance == null ? null : instance.getAccountId(), account.getId(), "instanceId");
        }
        if (input.getStackId() != null) {
            Stack stack = objectManager.loadResource(Stack.class, input.getStackId());
            validateOwned(stack == null ? null : stack.getAccountId(), account.getId(), "stackId");
        }
    }

    private static void validateOwned(Long actualAccountId, Long expectedAccountId, String field) {
        if (actualAccountId == null || !actualAccountId.equals(expectedAccountId)) {
            ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_REFERENCE, field);
        }
    }

    private static List<Host> placementHosts(List<Host> hosts, Long requestedHostId) {
        if (requestedHostId == null) {
            return hosts;
        }
        for (Host host : hosts) {
            if (requestedHostId.equals(host.getId())) {
                return Collections.singletonList(host);
            }
        }
        return Collections.emptyList();
    }

    private static String normalizedScope(StorageDriver driver) {
        String scope = DataAccessor.fieldString(driver, StorageDriverConstants.FIELD_SCOPE);
        return scope == null || scope.trim().length() == 0
                ? StorageDriverConstants.DEFAULT_SCOPE : scope.trim();
    }

    private static String normalizeDriver(String driver) {
        return driver == null ? "" : driver.trim();
    }

    private static boolean equalLong(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String overallStatus(List<VolumePreflightIssue> issues) {
        boolean warning = false;
        boolean unknown = false;
        for (VolumePreflightIssue issue : issues) {
            if ("blocked".equals(issue.getSeverity())) {
                return "blocked";
            }
            warning |= "warning".equals(issue.getSeverity());
            unknown |= "unknown".equals(issue.getSeverity());
        }
        return unknown ? "unknown" : (warning ? "warning" : "available");
    }

    private static String inventoryRevision(StorageDriver driver, List<Host> hosts,
            int availableHosts, List<VolumeSpec> specs) {
        List<Object> values = new ArrayList<Object>();
        values.add(driver == null ? null : driver.getId());
        values.add(driver == null ? null : driver.getState());
        for (Host host : hosts) {
            values.add(host.getId());
            values.add(host.getState());
        }
        values.add(Integer.valueOf(availableHosts));
        for (VolumeSpec spec : specs) {
            values.add(spec.raw);
        }
        return Integer.toHexString(values.hashCode());
    }

    private static VolumePreflightIssue issue(String severity, String reasonCode,
            Integer rowIndex, String value, String driverName) {
        VolumePreflightIssue issue = new VolumePreflightIssue();
        issue.setSeverity(severity);
        issue.setReasonCode(reasonCode);
        issue.setRowIndex(rowIndex);
        issue.setValue(value);
        issue.setDriverName(driverName);
        return issue;
    }

    enum VolumeKind {
        ANONYMOUS,
        NAMED,
        BIND
    }

    static class VolumeSpec {
        int index;
        String raw;
        String source;
        String target;
        String mode;
        VolumeKind kind;
        boolean valid;
        List<String> errors = new ArrayList<String>();
    }
}
