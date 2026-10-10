package io.cattle.platform.api.pubsub.subscribe;

import io.cattle.platform.eventing.model.EventVO;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Optional live authorization for the lifetime of a subscription. */
public interface SubscriptionAuthorization {
    Session capture(ApiRequest request);

    interface Session {
        /** null filters the event; a failure closes the subscription. */
        Object currentPolicy(EventVO<Object> event);
    }
}
