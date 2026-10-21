package io.github.ibuildthecloud.gdapi.request.handler.write;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WriteAuthorizationTest {
    @Test public void writeBarriersSurroundTheManagerExactlyOnce() throws Exception {
        List<String> events = new ArrayList<>();
        DefaultReadWriteApiDelegate delegate = delegate(events, false);
        delegate.write(new ApiRequest(null, null));
        assertEquals(List.of("before", "manager", "commit"), events);
    }

    @Test public void readRetainsItsOriginalPath() throws Exception {
        List<String> events = new ArrayList<>();
        delegate(events, false).read(new ApiRequest(null, null));
        assertEquals(List.of("manager"), events);
    }

    @Test public void lateDenialEscapesTheTransactionDelegate() throws Exception {
        List<String> events = new ArrayList<>();
        ClientVisibleException denied = assertThrows(ClientVisibleException.class,
                () -> delegate(events, true).write(new ApiRequest(null, null)));
        assertEquals(403, denied.getStatus());
        assertEquals(List.of("before", "manager", "commit"), events);
    }

    private DefaultReadWriteApiDelegate delegate(List<String> events, boolean deny) {
        DefaultReadWriteApiDelegate delegate = new DefaultReadWriteApiDelegate();
        delegate.setWriteAuthorizations(List.of(new WriteAuthorization() {
            @Override public void beforeWrite(ApiRequest request) { events.add("before"); }
            @Override public void beforeCommit(ApiRequest request) {
                events.add("commit");
                if (deny) throw new ClientVisibleException(403);
            }
        }));
        delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) { events.add("manager"); }
        }));
        return delegate;
    }
}
