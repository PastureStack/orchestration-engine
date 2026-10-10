package io.cattle.platform.api.resource;

/**
 * Server-built synthetic model whose public identity is a name, not a model
 * table ID. Consumers must still enforce live RBAC and known resource types.
 * Implementations must exclude these helper getters from API serialization.
 */
public interface NamedResourceIdentity {
    String getApiResourceType();
    String getApiResourceId();
}
