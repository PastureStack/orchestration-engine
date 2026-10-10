package io.cattle.platform.storage.service.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import io.cattle.platform.core.dao.GenericResourceDao;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.storage.pool.StoragePoolDriver;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

public class StorageServiceImageDependencyTest {
    StorageServiceImpl service;
    Instance instance;
    Image image;
    @Before public void setup() {
        service = new StorageServiceImpl();
        service.objectManager = mock(ObjectManager.class);
        service.processManager = mock(ObjectProcessManager.class);
        service.genericResourceDao = mock(GenericResourceDao.class);
        StoragePoolDriver driver = mock(StoragePoolDriver.class);
        service.drivers = List.of(driver);
        instance = mock(Instance.class); when(instance.getId()).thenReturn(3L);
        image = mock(Image.class); when(image.getId()).thenReturn(7L);
        when(service.objectManager.newRecord(Image.class)).thenReturn(image);
        when(service.objectManager.create(image)).thenReturn(image);
        when(service.objectManager.reload(image)).thenReturn(image);
        when(driver.populateImage("docker:busybox", image)).thenReturn(true);
    }
    @Test public void synchronousCreateSeesPersistedInstanceImageLink() {
        AtomicReference<Long> imageId = new AtomicReference<>();
        when(instance.getImageId()).thenAnswer(i -> imageId.get());
        doAnswer(i -> { imageId.set(7L); return instance; }).when(service.objectManager)
                .setFields(instance, INSTANCE.IMAGE_ID, 7L);
        doAnswer(i -> { assertEquals(Long.valueOf(7), instance.getImageId()); return null; })
                .when(service.processManager).scheduleStandardProcess(StandardProcess.CREATE, image, null);
        assertSame(image, service.registerRemoteImageForInstance("docker:busybox", instance));
        InOrder order = inOrder(service.objectManager, service.processManager);
        order.verify(service.objectManager).newRecord(Image.class);
        order.verify(service.objectManager).create(image);
        order.verify(service.objectManager).setFields(instance, INSTANCE.IMAGE_ID, 7L);
        order.verify(service.processManager).scheduleStandardProcess(StandardProcess.CREATE, image, null);
        verifyNoInteractions(service.genericResourceDao);
        verify(image, never()).setIsPublic(any());
    }
    @Test public void unpersistedInstanceIsRejectedBeforeAnyImageWrite() {
        when(instance.getId()).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.registerRemoteImageForInstance("docker:busybox", instance));
        verifyNoInteractions(service.objectManager, service.processManager, service.genericResourceDao);
    }
    @Test public void legacyRegistrationKeepsOriginalContract() {
        when(service.genericResourceDao.createAndSchedule(image)).thenReturn(image);
        assertSame(image, service.registerRemoteImage("docker:busybox"));
        verify(service.genericResourceDao).createAndSchedule(image);
        verifyNoInteractions(service.processManager);
        assertNull(service.registerRemoteImageForInstance(null, instance));
    }
    @Test public void unknownImageCannotCreateAnUnboundDependency() {
        assertThrows(IllegalArgumentException.class, () -> service.registerRemoteImageForInstance("unrecognized", instance));
        verify(service.objectManager, never()).create(image);
        verifyNoInteractions(service.processManager, service.genericResourceDao);
    }
}
