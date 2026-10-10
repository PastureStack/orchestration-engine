package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionAuthorization;
import io.cattle.platform.eventing.model.EventVO;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Map;
import jakarta.inject.Inject;

/** No cached role or Key secret survives the handshake. Every ping/event checks live state. */
public class ApiKeySubscriptionAuthorization implements SubscriptionAuthorization {
    @Inject ApiKeyDelegationService delegations;

    @Override public Session capture(ApiRequest request) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null || !key.restricted()) return null;
        Policy owner = (Policy) ApiContext.getContext().getPolicy();
        Map<String, Object> grant = Map.copyOf(delegations.metadata(key, owner.getAccountId(), request).entrySet().stream()
                .filter(entry -> entry.getValue() != null).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        delegations.current(grant); // Before any WebSocket/SSE writer upgrades.
        return event -> {
            var live = delegations.current(grant);
            if ("ping".equals(event.getName())) return live.authorization().policy();
            if (event.getResourceType() == null || event.getResourceId() == null) return null;
            try {
                Object resource = delegations.load(event.getResourceType(), event.getResourceId());
                delegations.check(live, event.getResourceType(), resource, "read");
                return live.authorization().policy();
            } catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException hidden) {
                return null; // A denied row never reaches event post-processors/body.
            }
        };
    }
}
