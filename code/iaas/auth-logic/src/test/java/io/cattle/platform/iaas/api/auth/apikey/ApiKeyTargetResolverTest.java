package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.resource.NamedResourceIdentity;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyTargetResolverTest {
    ApiKeyTargetResolver resolver;
    Policy owner;
    Stack stack;
    @Before public void setup() {
        resolver = new ApiKeyTargetResolver();
        resolver.objectManager = mock(ObjectManager.class);
        resolver.idFormatter = mock(IdFormatter.class);
        when(resolver.idFormatter.parseId(anyString())).thenAnswer(i -> i.<String>getArgument(0).replaceFirst("^1[a-z]+", ""));
        when(resolver.idFormatter.formatId(anyString(), any())).thenAnswer(i -> {
            String prefix = Map.of("project", "a", "stack", "st", "service", "s", "container", "i", "volume", "v").get(i.<String>getArgument(0));
            return "1" + prefix + i.getArgument(1);
        });
        owner = mock(Policy.class);
        when(owner.authorizeObject(any())).thenAnswer(i -> i.getArgument(0));
        when(owner.getAccountId()).thenReturn(5L);
        Account project = mock(Account.class);
        when(project.getKind()).thenReturn("project"); when(project.getId()).thenReturn(5L);
        when(resolver.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        stack = mock(Stack.class);
        when(stack.getId()).thenReturn(8L); when(stack.getAccountId()).thenReturn(5L);
        when(resolver.objectManager.loadResource(Stack.class, 8L)).thenReturn(stack);
    }
    private Instance instance() {
        Instance instance = mock(Instance.class);
        when(instance.getId()).thenReturn(3L); when(instance.getAccountId()).thenReturn(5L);
        when(instance.getServiceId()).thenReturn(null); when(instance.getStackId()).thenReturn(null);
        return instance;
    }
    @Test public void standaloneContainerUsesPersistedStackAndCanonicalType() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L);
        var target = resolver.resolveObject("instance", instance, owner);
        assertEquals("container", target.resourceType()); assertEquals("1i3", target.resourceId());
        assertEquals("1st8", target.stackId()); assertEquals("1a5", target.projectId());
    }
    @Test public void serviceFallbackHasAuthoritativeAccountAndStack() {
        Instance instance = instance(); when(instance.getServiceId()).thenReturn(2L);
        Service service = mock(Service.class);
        when(service.getStackId()).thenReturn(8L); when(service.getAccountId()).thenReturn(5L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        assertEquals("1st8", resolver.resolveObject("instance", instance, owner).stackId());
        when(service.getAccountId()).thenReturn(6L);
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveObject("instance", instance, owner)).getCode());
    }
    @Test public void contradictoryServiceAndContainerStackCannotPass() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L); when(instance.getServiceId()).thenReturn(2L);
        Service service = mock(Service.class); when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(9L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
    }
    @Test public void volumeUsesItsOwnPersistedStack() {
        Volume volume = mock(Volume.class); when(volume.getId()).thenReturn(4L);
        when(volume.getAccountId()).thenReturn(5L); when(volume.getStackId()).thenReturn(8L);
        assertEquals("1st8", resolver.resolveObject("volume", volume, owner).stackId());
    }
    @Test public void typedParentCannotUseUnrelatedPrefix() {
        assertEquals(Long.valueOf(8), resolver.parseScopeId("stack", "1st8"));
        assertThrows(ClientVisibleException.class, () -> resolver.parseScopeId("stack", "1s8"));
        assertThrows(ClientVisibleException.class, () -> resolver.parseScopeId("stack", "8"));
    }
    @Test public void createPayloadLoadsParentInsteadOfTrustingItsAccount() {
        ApiRequest request = new ApiRequest(null, null); request.setType("service"); request.setMethod("POST");
        request.setRequestObject(Map.of("stackId", "1st8", "accountId", "1a6"));
        assertThrows(ClientVisibleException.class, () -> resolver.resolve(request, owner));
        request.setRequestObject(Map.of("stackId", "1st8", "accountId", "1a5"));
        assertEquals("1st8", resolver.resolve(request, owner).getFirst().stackId());
    }
    @Test public void namedSettingsAreNotParsedAsNumericIds() {
        ApiRequest request = new ApiRequest(null, null); request.setType("setting"); request.setMethod("GET");
        request.setId("api.host");
        var target = resolver.resolve(request, owner).getFirst();
        assertEquals("api.host", target.resourceId()); assertEquals(ApiKeyPolicyEvaluator.TargetLevel.PLATFORM, target.level());
        verifyNoInteractions(resolver.objectManager);
    }
    @Test public void syntheticIdentityUsesExplicitModelContractNotUnknownTypeGuess() {
        NamedResourceIdentity setting = mock(NamedResourceIdentity.class);
        when(setting.getApiResourceType()).thenReturn("setting"); when(setting.getApiResourceId()).thenReturn("api.host");
        var target = resolver.resolveObject("activeSetting", setting, owner);
        assertEquals("setting", target.resourceType()); assertEquals("api.host", target.resourceId());
        verifyNoInteractions(resolver.idFormatter);
        when(setting.getApiResourceType()).thenReturn("futurePlugin");
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("unknown", setting, owner));
    }
}
