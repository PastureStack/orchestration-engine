package io.cattle.platform.iaas.api.filter.projecttemplate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.DefaultPolicy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.core.model.tables.records.ProjectTemplateRecord;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;

import java.net.URI;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProjectTemplateActionOutputFilterTest {

    private final ProjectTemplateActionOutputFilter filter = new ProjectTemplateActionOutputFilter();

    @Before
    public void setUp() {
        ApiContext.newContext().setPolicy(policy(false));
    }

    @After
    public void tearDown() {
        ApiContext.remove();
    }

    @Test
    public void publicTemplateDoesNotAdvertiseRemoveToNonAdmin() throws Exception {
        ProjectTemplateRecord template = template(null, true);
        ResourceImpl ownerView = resourceWithRemove();
        ResourceImpl readonlyView = new ResourceImpl();

        assertSame(ownerView, filter.filter(null, template, ownerView));
        assertFalse(ownerView.getActions().containsKey("remove"));
        assertSame(readonlyView, filter.filter(null, template, readonlyView));
        assertFalse(readonlyView.getActions().containsKey("remove"));
    }

    @Test
    public void privateTemplateOwnerKeepsRemoveAction() throws Exception {
        ResourceImpl resource = resourceWithRemove();

        filter.filter(null, template(42L, false), resource);

        assertTrue(resource.getActions().containsKey("remove"));
    }

    @Test
    public void adminKeepsRemoveActionForPublicTemplate() throws Exception {
        ApiContext.getContext().setPolicy(policy(true));
        ResourceImpl resource = resourceWithRemove();

        filter.filter(null, template(null, true), resource);

        assertTrue(resource.getActions().containsKey("remove"));
    }

    private static ProjectTemplateRecord template(Long accountId, boolean isPublic) {
        ProjectTemplateRecord template = new ProjectTemplateRecord();
        template.setAccountId(accountId);
        template.setIsPublic(isPublic);
        return template;
    }

    private static ResourceImpl resourceWithRemove() throws Exception {
        ResourceImpl resource = new ResourceImpl();
        resource.getActions().put("remove", URI.create("http://localhost/projecttemplates/1pt5?action=remove").toURL());
        return resource;
    }

    private static Policy policy(final boolean admin) {
        return new DefaultPolicy(42L, 42L, "user42", Collections.<Identity>emptySet(), new NoPolicyOptions()) {
            @Override
            public boolean isOption(String optionName) {
                return admin && Policy.AUTHORIZED_FOR_ALL_ACCOUNTS.equals(optionName);
            }
        };
    }
}
