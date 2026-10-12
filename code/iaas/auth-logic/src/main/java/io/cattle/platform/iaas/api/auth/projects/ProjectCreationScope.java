package io.cattle.platform.iaas.api.auth.projects;

import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;

/** Binds work to the project actually created by the project manager, never an input ID. */
public final class ProjectCreationScope implements AutoCloseable {
    private static final ThreadLocal<ProjectCreationScope> CURRENT = new ThreadLocal<>();
    private final ProjectCreationScope previous;
    private Long projectId;

    private ProjectCreationScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static ProjectCreationScope open() { return new ProjectCreationScope(); }

    static void bindCreated(Account project) {
        ProjectCreationScope scope = CURRENT.get();
        if (scope == null || scope.projectId != null || project == null || project.getId() == null
                || !ProjectConstants.TYPE.equals(project.getKind())) {
            throw new IllegalStateException("Invalid project creation scope");
        }
        scope.projectId = project.getId();
    }

    public static Long currentProjectId() {
        ProjectCreationScope scope = CURRENT.get();
        return scope == null ? null : scope.projectId;
    }

    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Project creation scope ownership changed");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
