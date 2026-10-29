package io.cattle.platform.servicediscovery.api.service;

import java.util.function.Supplier;

/** Archive eligibility and its existing lifecycle lease, not a resource writer or authority grant. */
public interface ResourceArchiveChange {
    /** The caller supplies its authorized, fresh locked target and performs only the normal filtered PUT. */
    <T> T withChange(Object lockedTarget, boolean archived, Supplier<T> change);
}
