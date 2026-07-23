package io.cattle.platform.iaas.api.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.model.Account;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class AbstractTokenUtilIdListTest {

    @Test
    public void identitiesUseCheckedIdListBoundary() {
        TestTokenUtil util = new TestTokenUtil();
        Map<String, Object> jsonData = new HashMap<String, Object>();
        jsonData.put(AbstractTokenUtil.ID_LIST, Arrays.asList("user:alice", "team:devs", "invalid"));

        Set<Identity> identities = util.identities(jsonData);

        assertEquals(2, identities.size());
        assertTrue(identities.contains(new Identity("user", "alice")));
        assertTrue(identities.contains(new Identity("team", "devs")));
    }

    @Test
    public void stringListRejectsNonListTokenFieldLikeLegacyCast() {
        try {
            AbstractTokenUtil.stringList("user:alice");
            fail("Expected non-list token field to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.List"));
        }
    }

    @Test
    public void stringListRejectsNonStringElementsLikeLegacyEnhancedForLoop() {
        try {
            AbstractTokenUtil.stringList(Arrays.asList("user:alice", 1));
            fail("Expected non-string token id to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.lang.String"));
        }
    }

    @Test
    public void isAllowedReceivesCheckedIdList() {
        TestTokenUtil util = new TestTokenUtil();
        Map<String, Object> jsonData = new HashMap<String, Object>();
        jsonData.put(AbstractTokenUtil.ID_LIST, Arrays.asList("user:alice"));

        assertTrue(util.isAllowed(jsonData));
        assertEquals(Arrays.asList("user:alice"), util.seenIdList);
    }

    private static class TestTokenUtil extends AbstractTokenUtil {
        private List<String> seenIdList;

        @Override
        protected boolean isWhitelisted(List<String> idList) {
            seenIdList = idList;
            return true;
        }

        @Override
        protected String accessMode() {
            return REQUIRED_ACCESSMODE;
        }

        @Override
        protected String accessToken() {
            return null;
        }

        @Override
        protected void postAuthModification(Account account) {
        }

        @Override
        public String tokenType() {
            return "testjwt";
        }

        @Override
        public String userType() {
            return "user";
        }

        @Override
        public boolean createAccount() {
            return false;
        }

        @Override
        public String getName() {
            return "test";
        }
    }
}
