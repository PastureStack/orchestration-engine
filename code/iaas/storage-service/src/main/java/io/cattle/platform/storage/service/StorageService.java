package io.cattle.platform.storage.service;

import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.StorageDriver;

public interface StorageService {

    Image registerRemoteImage(String uuid);

    /** Persist the instance/image relationship before the image create process runs. */
    Image registerRemoteImageForInstance(String uuid, Instance instance);

    boolean isValidUUID(String uuid);

    void setupPools(StorageDriver storageDriver);

}
