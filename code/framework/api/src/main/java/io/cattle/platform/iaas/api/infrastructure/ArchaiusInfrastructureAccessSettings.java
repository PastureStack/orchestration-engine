package io.cattle.platform.iaas.api.infrastructure;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusInfrastructureAccessSettings implements InfrastructureAccessSettings {

    private final ConfigProperty<String> modifyInfrastructureRoles = ArchaiusUtil.getStringProperty(
            "modify.infrastructure.roles");

    static InfrastructureAccessSettings create() {
        return new ArchaiusInfrastructureAccessSettings();
    }

    @Override
    public String modifyInfrastructureRoles() {
        return modifyInfrastructureRoles.get();
    }

    @Override
    public void addModifyInfrastructureRolesCallback(Runnable callback) {
        modifyInfrastructureRoles.addCallback(callback);
    }
}
