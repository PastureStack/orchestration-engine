package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiStackGraphReadTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void graphOptInKeepsBothNativeEndpointsParentsAndHiddenCandidateCountsPrivate() {
        Fixture f = new Fixture();
        Map<?, ?> ordinary = f.detail(false);
        assertFalse(ordinary.containsKey("stackGraph"));
        verify(f.dao, never()).getStackGraphLinks(anyLong(), anyLong(), anyList());
        Map<?, ?> graph = f.graph();
        assertEquals(2, ((List<?>) graph.get("nodes")).size()); assertEquals(1, ((List<?>) graph.get("edges")).size());
        assertEquals("unknown", graph.get("completeness")); assertEquals("unknown", graph.get("permissionCompleteness"));
        assertEquals(false, graph.get("limitReached")); assertFalse(graph.toString().contains("SECRET_PAYLOAD"));
        assertFalse(graph.toString().contains("foreign")); assertFalse(graph.toString().contains("alias"));
        f.links.clear(); f.links.add(new ServiceDao.GraphLink(8, 9));
        for (int id = 100; id < 132; id++) f.links.add(new ServiceDao.GraphLink(8, id));
        assertEquals(graph, f.graph()); // Even the 33-candidate sentinel reveals no hidden-count flag.
        f.nativeScope = (type, id) -> !"stack".equals(type) || !"12".equals(id);
        Map<?, ?> hiddenParent = f.graph();
        assertEquals(1, ((List<?>) hiddenParent.get("nodes")).size()); assertTrue(((List<?>) hiddenParent.get("edges")).isEmpty());
        assertFalse(hiddenParent.toString().contains("target")); assertEquals(false, hiddenParent.get("limitReached"));
        f.nativeScope = (type, id) -> true; f.target.setState("purging");
        assertEquals(1, ((List<?>) f.graph().get("nodes")).size());
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest()); assertSame(f.owner, ApiContext.getContext().getPolicy());
        verify(f.manager, never()).update(anyString(), anyString(), any());
        verify(f.manager, never()).resourceAction(anyString(), any());
    }

    @Test public void graphRejectsWrongSelectorReadDenialRevocationAndLateUuidReplacementWithoutFallback() {
        Fixture f = new Fixture();
        assertEquals("ResourceViewInputInvalid", code(() -> f.service.read(f.request(
                Map.of("resourceType", "service", "resourceId", "8", "view", "stack_graph")))));
        when(f.schema.getResourceMethods()).thenReturn(List.of());
        assertEquals("OwnerPermissionDenied", code(f::graph));
        final Fixture revoked = new Fixture();
        revoked.nativeScope = (type, id) -> {
            if ("service".equals(type) && "9".equals(id)) throw new ClientVisibleException(403, "ApiKeyRevoked"); return true;
        };
        assertEquals("ApiKeyRevoked", code(revoked::graph));
        final Fixture stale = new Fixture();
        when(stale.dao.getStackGraphLinks(anyLong(), anyLong(), anyList())).thenAnswer(call -> {
            stale.source.setUuid("replacement"); return stale.links;
        });
        assertEquals("ResourceViewUnavailable", code(stale::graph));
        final Fixture project = new Fixture();
        when(project.dao.getStackGraphLinks(anyLong(), anyLong(), anyList())).thenAnswer(call -> {
            project.project.setUuid("replacement"); return project.links;
        });
        assertEquals("ResourceViewUnavailable", code(project::graph));
        final Fixture changedKind = new Fixture();
        when(changedKind.dao.getStackGraphLinks(anyLong(), anyLong(), anyList())).thenAnswer(call -> {
            changedKind.project.setKind("user"); return changedKind.links;
        });
        assertEquals("ResourceViewUnavailable", code(changedKind::graph));
        Fixture unreadable = new Fixture(); when(unreadable.fields.get("linkedServices").isIncludeInList()).thenReturn(false);
        assertTrue(((List<?>) unreadable.graph().get("nodes")).isEmpty());
        verify(unreadable.manager, never()).list(eq("service"), any());
    }

    @Test public void graphRejectsSourceUuidAndStackReplacementBetweenNativeReads() {
        for (boolean replaceUuid : List.of(true, false)) {
            Fixture f = new Fixture();
            ServiceRecord replacement = Fixture.service(8, 77, replaceUuid ? 7 : 12, "replacement");
            if (!replaceUuid) replacement.setUuid(f.source.getUuid());
            when(f.manager.list(eq("service"), any())).thenAnswer(call -> {
                f.nativeRows.put("service:8", replacement);
                return List.of(f.source);
            });
            assertEquals("ResourceViewUnavailable", code(f::graph));
            verify(f.dao, never()).getStackGraphLinks(anyLong(), anyLong(), anyList());
        }
    }

    private static String code(Runnable action) { return assertThrows(ClientVisibleException.class, action::run).getCode(); }

    static class Fixture extends AiVolumeUsageViewTest.Fixture {
        final ServiceDao dao = mock(ServiceDao.class);
        final AccountRecord project = new AccountRecord();
        final StackRecord root = stack(7, "root"), other = stack(12, "other"), hidden = stack(13, "hidden");
        final ServiceRecord source = service(8, 77, 7, "source"), target = service(9, 77, 12, "target");
        final List<ServiceDao.GraphLink> links = new ArrayList<>(List.of(new ServiceDao.GraphLink(8, 9),
                new ServiceDao.GraphLink(8, 10), new ServiceDao.GraphLink(8, 99), new ServiceDao.GraphLink(8, 11)));
        Fixture() {
            service.services = dao; service.objects = mock(ObjectManager.class);
            project.setId(77L); project.setUuid("project-uuid"); project.setKind("project"); project.setState("active");
            nativeRows.put("stack:7", root); nativeRows.put("stack:12", other); nativeRows.put("stack:13", hidden);
            nativeRows.put("service:8", source); nativeRows.put("service:9", target);
            nativeRows.put("service:99", service(99, 88, 7, "foreign"));
            nativeRows.put("service:11", service(11, 77, 13, "private"));
            nativeScope = (type, id) -> !"stack".equals(type) || !"13".equals(id);
            Field linksField = mock(Field.class); when(linksField.isIncludeInList()).thenReturn(true); fields.put("linkedServices", linksField);
            when(service.objects.loadResource(eq(Account.class), anyLong())).thenReturn(project);
            when(service.objects.loadResource(eq(Stack.class), anyLong())).thenAnswer(call -> nativeRows.get("stack:" + call.getArgument(1)));
            when(service.objects.loadResource(eq(Service.class), anyLong())).thenAnswer(call -> nativeRows.get("service:" + call.getArgument(1)));
            when(manager.list(eq("service"), any())).thenAnswer(call -> {
                io.github.ibuildthecloud.gdapi.request.ApiRequest request = call.getArgument(1);
                assertEquals(17, request.getPagination().getLimit().intValue()); assertTrue(request.getConditions().containsKey("stackId"));
                return List.of(source);
            });
            when(dao.getStackGraphLinks(eq(77L), eq(7L), anyList())).thenAnswer(call -> {
                List<Long> ids = call.getArgument(2); if (ids.isEmpty()) return List.of();
                assertEquals(List.of(8L), ids); return links;
            });
            when(manager.convertResponse(eq(root), any())).thenAnswer(call -> {
                ResourceImpl r = new ResourceImpl(); r.setId("7"); r.setType("stack");
                r.setFields(Map.of("name", "root", "state", "inactive", "dockerCompose", "SECRET_PAYLOAD")); return r;
            });
        }
        Map<?, ?> detail(boolean graph) {
            return (Map<?, ?>) ((List<?>) service.read(request(graph ? Map.of("resourceType", "stack", "resourceId", "7", "view", "stack_graph")
                    : Map.of("resourceType", "stack", "resourceId", "7"))).get("data")).getFirst();
        }
        Map<?, ?> graph() { return (Map<?, ?>) detail(true).get("stackGraph"); }
        private static StackRecord stack(long id, String name) {
            StackRecord row = new StackRecord(); row.setId(id); row.setAccountId(77L); row.setUuid(name + "-uuid");
            row.setKind("stack"); row.setName(name); row.setState("inactive"); return row;
        }
        private static ServiceRecord service(long id, long account, long stack, String name) {
            ServiceRecord row = new ServiceRecord(); row.setId(id); row.setAccountId(account); row.setStackId(stack); row.setUuid(name + "-uuid");
            row.setKind("service"); row.setName("same"); row.setState("inactive"); row.setData(Map.of("SECRET_PAYLOAD", "PRIVATE_ENV")); return row;
        }
    }
}
