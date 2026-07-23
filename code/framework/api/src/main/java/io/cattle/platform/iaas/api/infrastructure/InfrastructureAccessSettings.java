package io.cattle.platform.iaas.api.infrastructure;

interface InfrastructureAccessSettings {
    String modifyInfrastructureRoles();

    void addModifyInfrastructureRolesCallback(Runnable callback);
}
