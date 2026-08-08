package io.cattle.platform.iaas.api.infrastructure;

import io.cattle.platform.api.auth.Policy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


public class InfrastructureAccessManagerImpl implements InfrastructureAccessManager {

    private static final InfrastructureAccessSettings DEFAULT_SETTINGS = ArchaiusInfrastructureAccessSettings.create();

    private final InfrastructureAccessSettings settings;
    private Set<String> modifyInfraRoles = new HashSet<>();

    public InfrastructureAccessManagerImpl() {
        this(DEFAULT_SETTINGS);
    }

    InfrastructureAccessManagerImpl(InfrastructureAccessSettings settings) {
        super();
        if (settings == null) {
            throw new IllegalArgumentException("Infrastructure access settings are required");
        }
        this.settings = settings;
        reloadModifyInfrastructureRoles();
        this.settings.addModifyInfrastructureRolesCallback(new Runnable() {
            @Override
            public void run() {
                reloadModifyInfrastructureRoles();
            }
        });
    }

    void reloadModifyInfrastructureRoles() {
        String prop = settings.modifyInfrastructureRoles();
        Set<String> roles = new HashSet<>(Arrays.asList(prop.split(",")));
        modifyInfraRoles = roles;
    }

    @Override
    public boolean canModifyInfrastructure(Policy policy) {
        // Return true if modifyInfraRoles contains any of the roles in
        // policy.getRoles
        return modifyInfraRoles.stream().anyMatch(policy.getRoles()::contains);
    }
}
