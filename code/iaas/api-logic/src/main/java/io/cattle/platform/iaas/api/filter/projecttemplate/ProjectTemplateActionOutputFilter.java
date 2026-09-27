package io.cattle.platform.iaas.api.filter.projecttemplate;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.ProjectTemplate;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.response.ResourceOutputFilter;

public class ProjectTemplateActionOutputFilter implements ResourceOutputFilter {

    @Override
    public Resource filter(ApiRequest request, Object original, Resource converted) {
        if (!(original instanceof ProjectTemplate)) {
            return converted;
        }

        ProjectTemplate template = (ProjectTemplate) original;
        Policy policy = ApiUtils.getPolicy();
        if (!policy.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS)
                && (template.getAccountId() == null || template.getAccountId().longValue() != policy.getAccountId())) {
            converted.getActions().remove("remove");
        }
        return converted;
    }

    @Override
    public String[] getTypes() {
        return new String[] { "projectTemplate" };
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] { ProjectTemplate.class };
    }
}
