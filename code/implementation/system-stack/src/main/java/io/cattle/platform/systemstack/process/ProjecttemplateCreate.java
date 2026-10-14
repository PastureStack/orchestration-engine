package io.cattle.platform.systemstack.process;

import static io.cattle.platform.core.model.tables.AccountTable.*;

import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.ProjectTemplate;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.process.base.AbstractDefaultProcessHandler;
import io.cattle.platform.sample.data.SampleDataStartupV3;
import io.cattle.platform.systemstack.catalog.CatalogService;
import io.cattle.platform.systemstack.lock.ProjectTemplateLoadLock;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class ProjecttemplateCreate extends AbstractDefaultProcessHandler {

    @Inject
    SystemStackTrigger systemStackTrigger;
    @Inject
    SampleDataStartupV3 sampleDataStartupV3;
    @Inject
    LockManager lockManager;

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        ProjectTemplate template = (ProjectTemplate)state.getResource();
        lockManager.lock(new ProjectTemplateLoadLock(), () -> {
            assignDefaultProjectWithLock(template);
            return null;
        });
        return null;
    }

    /** Called by template creation and catalog reload while holding ProjectTemplateLoadLock. */
    public void assignDefaultProjectWithLock(ProjectTemplate template) {
        if (!Boolean.TRUE.equals(template.getIsPublic()) ||
                template.getRemoved() != null ||
                !CatalogService.DEFAULT_TEMPLATE.get().equalsIgnoreCase(template.getName())) {
            return;
        }

        sampleDataStartupV3.start();
        Account defaultProject = getDefaultProject();
        if (defaultProject == null || defaultProject.getRemoved() != null ||
                !ProjectConstants.DEFAULT_PROJECT_UUID.equals(defaultProject.getUuid()) ||
                !AccountConstants.PROJECT_KIND.equals(defaultProject.getKind()) ||
                defaultProject.getProjectTemplateId() != null) {
            return;
        }

        if (!AccountConstants.ACCOUNT_VERSION.get().equals(defaultProject.getVersion())) {
            return;
        }

        objectManager.setFields(defaultProject,
                ACCOUNT.PROJECT_TEMPLATE_ID, template.getId());
        systemStackTrigger.trigger(defaultProject.getId());
    }

    protected Account getDefaultProject() {
        return objectManager.findAny(Account.class,
                ObjectMetaDataManager.UUID_FIELD, ProjectConstants.DEFAULT_PROJECT_UUID);
    }

}
