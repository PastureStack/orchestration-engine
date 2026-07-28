package io.cattle.platform.async.retry;

import static org.junit.Assert.*;

import io.cattle.platform.util.concurrent.DelayedObject;

import org.junit.Test;

public class RetryTest {

    @Test
    public void equalsDelayedObjectContainingThisRetry() {
        Retry retry = new Retry(3, 1000L, null, null);
        DelayedObject<Retry> delayed = new DelayedObject<Retry>(System.currentTimeMillis(), retry);

        assertTrue(retry.equals(delayed));
    }

    @Test
    public void doesNotEqualDelayedObjectContainingDifferentRetry() {
        Retry retry = new Retry(3, 1000L, null, null);
        Retry other = new Retry(3, 1000L, null, null);
        DelayedObject<Retry> delayed = new DelayedObject<Retry>(System.currentTimeMillis(), other);

        assertFalse(retry.equals(delayed));
    }

    @Test
    public void fallsBackToIdentityForNonDelayedObjects() {
        Retry retry = new Retry(3, 1000L, null, null);

        assertTrue(retry.equals(retry));
        assertFalse(retry.equals("not-delayed"));
    }

}
