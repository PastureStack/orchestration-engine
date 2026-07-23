package io.cattle.platform.api.pubsub.subscribe.jetty;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.pubsub.subscribe.MessageWriter;
import io.cattle.platform.api.pubsub.subscribe.NonBlockingSubscriptionHandler;
import io.cattle.platform.api.pubsub.util.SubscriptionUtils;
import io.cattle.platform.api.pubsub.util.SubscriptionUtils.SubscriptionStyle;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.iaas.event.IaasEvents;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServerContainer;

public class JettyWebSocketSubcriptionHandler extends NonBlockingSubscriptionHandler {

    @Override
    protected MessageWriter getMessageWriter(ApiRequest apiRequest) throws IOException {
        HttpServletRequest req = apiRequest.getServletContext().getRequest();
        HttpServletResponse resp = apiRequest.getServletContext().getResponse();
        Policy policy = ApiUtils.getPolicy();
        String identifier = null;
        SubscriptionStyle style = SubscriptionUtils.getSubscriptionStyle(policy);
        if (SubscriptionStyle.QUALIFIED.equals(style)) {
            String key = SubscriptionUtils.getSubscriptionQualifier(policy);
            String value = SubscriptionUtils.getSubscriptionQualifierValue(policy);
            if (IaasEvents.AGENT_QUALIFIER.equals(key) && StringUtils.isNotEmpty(value)) {
                identifier = String.format("%s [%s]", key, value);
            }
        }
        final WebSocketMessageWriter messageWriter = new WebSocketMessageWriter(identifier);
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.ensureContainer(req.getServletContext());
        JettyWebSocketCreator creator = new JettyWebSocketCreator() {
            @Override
            public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp) {
                return messageWriter;
            }
        };

        if ("websocket".equalsIgnoreCase(req.getHeader("Upgrade")) && container.upgrade(creator, req, resp)) {
            apiRequest.setResponseCode(101);
            apiRequest.commit();
            return messageWriter;
        } else {
            return null;
        }
    }

}