package io.cattle.platform.iaas.api.auth.projects;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.List;
import java.util.Map;
import jakarta.inject.Inject;

public class SetProjectMembersActionHandler implements ActionHandler {

    @Inject
    AuthDao authDao;

    @Inject
    ProjectMemberResourceManager projectMemberResourceManager;

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {
        Account project = (Account) obj;
        project = authDao.getAccountById(project.getId());
        if (project == null) {
            throw new ClientVisibleException(ResponseCodes.NOT_FOUND);
        }
        List<Map<String, String>> members = ProjectMemberInput.memberMapList(
                ProjectMemberInput.requestField(request.getRequestObject(), ProjectMemberInput.MEMBERS));
        return projectMemberResourceManager.setMembers(project, members, false);
    }

    @Override
    public String getName() {
        return "account.setmembers";
    }
}

