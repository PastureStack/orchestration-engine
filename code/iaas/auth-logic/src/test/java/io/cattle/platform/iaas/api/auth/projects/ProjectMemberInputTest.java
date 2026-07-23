package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ProjectMemberInputTest {

    @Test
    public void memberMapListKeepsProjectMemberFields() {
        Map<String, Object> member = new LinkedHashMap<String, Object>();
        member.put("externalId", "alice");
        member.put("externalIdType", "user");
        member.put("role", "owner");

        List<Map<String, String>> members = ProjectMemberInput.memberMapList(Arrays.asList(member));

        assertEquals(1, members.size());
        assertEquals("alice", members.get(0).get("externalId"));
        assertEquals("user", members.get(0).get("externalIdType"));
        assertEquals("owner", members.get(0).get("role"));
    }

    @Test
    public void memberMapListAllowsNullLikeLegacyCast() {
        assertEquals(null, ProjectMemberInput.memberMapList(null));
    }

    @Test
    public void memberMapListRejectsNonListMembers() {
        try {
            ProjectMemberInput.memberMapList("user:alice");
            fail("Expected non-list members to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.List"));
        }
    }

    @Test
    public void memberMapListRejectsNonMapEntries() {
        try {
            ProjectMemberInput.memberMapList(Arrays.asList("user:alice"));
            fail("Expected non-map member entry to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.Map"));
        }
    }

    @Test
    public void memberMapListRejectsNonStringProjectMemberFields() {
        Map<String, Object> member = new LinkedHashMap<String, Object>();
        member.put("externalId", "alice");
        member.put("externalIdType", "user");
        member.put("role", 1);

        try {
            ProjectMemberInput.memberMapList(Arrays.asList(member));
            fail("Expected non-string project member field to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.lang.String"));
        }
    }

    @Test
    public void memberMapListIgnoresUnknownNonStringFieldsLikeSetMembers() {
        Map<String, Object> member = new LinkedHashMap<String, Object>();
        member.put("externalId", "alice");
        member.put("externalIdType", "user");
        member.put("role", "owner");
        member.put("ignored", 1);

        List<Map<String, String>> members = ProjectMemberInput.memberMapList(Arrays.asList(member));

        assertEquals("owner", members.get(0).get("role"));
        assertEquals(null, members.get(0).get("ignored"));
    }

    @Test
    public void requestFieldUsesMapBoundary() {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put(ProjectMemberInput.MEMBERS, Arrays.asList(new LinkedHashMap<String, Object>()));

        assertEquals(request.get(ProjectMemberInput.MEMBERS),
                ProjectMemberInput.requestField(request, ProjectMemberInput.MEMBERS));
    }
}
