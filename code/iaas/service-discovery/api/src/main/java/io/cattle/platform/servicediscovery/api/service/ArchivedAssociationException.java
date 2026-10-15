package io.cattle.platform.servicediscovery.api.service;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

/** Expected selector-registration outcome; unrelated instance starts must still finish. */
public class ArchivedAssociationException extends ClientVisibleException {
    public ArchivedAssociationException() {
        super(409, "ResourceArchived", "Restore the service and parent stack before attaching new instances.", null);
    }
}
