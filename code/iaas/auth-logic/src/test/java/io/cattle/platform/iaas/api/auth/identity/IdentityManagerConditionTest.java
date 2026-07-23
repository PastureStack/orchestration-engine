package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;

import java.util.Arrays;

import org.junit.Test;

public class IdentityManagerConditionTest {

    @Test
    public void firstConditionKeepsFirstSearchCondition() {
        Condition condition = new Condition(ConditionType.EQ, "alice");

        assertEquals(condition, IdentityManager.firstCondition(Arrays.asList(condition)));
    }

    @Test
    public void firstConditionRejectsNonListCriteriaLikeLegacyCast() {
        try {
            IdentityManager.firstCondition("alice");
            fail("Expected non-list criteria to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.List"));
        }
    }

    @Test
    public void firstConditionRejectsNonConditionEntryLikeLegacyElementCast() {
        try {
            IdentityManager.firstCondition(Arrays.asList("alice"));
            fail("Expected non-condition criteria entry to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("Condition"));
        }
    }

    @Test
    public void firstConditionKeepsEmptyListFailureBehavior() {
        try {
            IdentityManager.firstCondition(Arrays.asList());
            fail("Expected empty criteria list to be rejected");
        } catch (IndexOutOfBoundsException e) {
            assertTrue(e.getMessage().contains("Index"));
        }
    }
}
