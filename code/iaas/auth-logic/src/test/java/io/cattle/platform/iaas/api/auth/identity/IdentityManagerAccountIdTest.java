package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;

import org.junit.Test;

public class IdentityManagerAccountIdTest {

    private final IdFormatter formatter = new IdFormatter() {
        @Override
        public Object formatId(String type, Object id) {
            return "1a" + id;
        }

        @Override
        public String parseId(String id) {
            return id != null && id.startsWith("1a") ? id.substring(2) : null;
        }

        @Override
        public IdFormatter withSchemaFactory(SchemaFactory schemaFactory) {
            return this;
        }
    };

    @Test
    public void acceptsStoredNumericAccountId() {
        assertEquals(Long.valueOf(12L), IdentityManager.localAccountId("12", formatter));
    }

    @Test
    public void acceptsFormattedPublicAccountId() {
        assertEquals(Long.valueOf(12L), IdentityManager.localAccountId("1a12", formatter));
    }

    @Test
    public void rejectsNonAccountIdentity() {
        assertNull(IdentityManager.localAccountId("oidc-subject", formatter));
    }
}
