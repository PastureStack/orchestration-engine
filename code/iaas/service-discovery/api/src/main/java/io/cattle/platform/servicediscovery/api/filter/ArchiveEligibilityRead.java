package io.cattle.platform.servicediscovery.api.filter;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;

/** Only AI archive validation uses a fresh read transaction; it never writes or reloads the locked target. */
@Named
public class ArchiveEligibilityRead {
    @Inject Provider<ArchiveValidationFilter> owner;

    public void validate(Object lockedTarget, boolean archived) {
        if (archived && !ArchiveValidationFilter.archived(lockedTarget)) owner.get().assertArchivable(lockedTarget);
    }
}
