package io.cattle.platform.api.handler;

import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.deferred.util.DeferredUtils;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.framework.event.FrameworkEvents;
import io.cattle.platform.util.exception.ExceptionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema.Method;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ApiRequestHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;


public class EventNotificationHandler implements ApiRequestHandler {

    private static final EventNotificationSettings DEFAULT_SETTINGS = ArchaiusEventNotificationSettings.create();

    private final EventNotificationSettings settings;
    EventService eventService;
    Set<String> excludeTypes = new HashSet<String>();

    public EventNotificationHandler() {
        this(DEFAULT_SETTINGS);
    }

    EventNotificationHandler(EventNotificationSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Event notification settings are required");
        }
        this.settings = settings;
    }

    @Override
    public void handle(ApiRequest request) throws IOException {
        if (Method.GET.isMethod(request.getMethod())) {
            return;
        }

        String type = request.getSchemaFactory().getBaseType(request.getType());

        if (excludeTypes.contains(type)) {
            return;
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("method", request.getMethod().toString());
        data.put("id", request.getId());
        data.put("type", type);
        data.put("action", request.getAction());
        data.put("responseCode", request.getResponseCode());
        data.put("accountId", ApiUtils.getPolicy().getAccountId());

        DeferredUtils.deferPublish(eventService, EventVO.newEvent(FrameworkEvents.API_CHANGE).withResourceId(request.getId()).withResourceType(
                type).withData(data));
    }

    @Override
    public boolean handleException(ApiRequest request, Throwable t) throws IOException, ServletException {
        if (t instanceof ClientVisibleException) {
            return false;
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("method", request.getMethod().toString());
        data.put("id", request.getId());
        data.put("type", request.getType());
        data.put("action", request.getAction());

        data.put("message", t.getMessage());
        data.put("stackTrace", ExceptionUtils.toString(t));

        DeferredUtils.deferPublish(eventService, EventVO.newEvent(FrameworkEvents.API_EXCEPTION).withData(data));

        return false;
    }

    @PostConstruct
    public void init() {
        load();
        settings.addExcludeTypesCallback(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    public void load() {
        excludeTypes = new HashSet<String>();
        excludeTypes.addAll(settings.excludeTypes());
    }

    boolean isExcludedType(String type) {
        return excludeTypes.contains(type);
    }

    public EventService getEventService() {
        return eventService;
    }

    @Inject
    public void setEventService(EventService eventService) {
        this.eventService = eventService;
    }

}
