package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.api.resource.NamedResourceIdentity;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.ServiceExposeMap;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Map;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyTargetResolverTest {
    ApiKeyTargetResolver resolver;
    Policy owner;
    Stack stack;
    @Test public void imageDependencyUsesPersistedInstanceNotImageVisibility() {
        Instance instance = imageInstance();
        Image image = image();
        when(owner.authorizeObject(image)).thenReturn(null);
        var dependency = resolver.resolveImageDependency(instance, image, owner);
        var target = dependency.scope();
        assertEquals("image", target.resourceType()); assertEquals("1st8", target.stackId());
        assertEquals("1a5", target.projectId());
        assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, resolver.resolveObject("image", image(), owner).level());
    }
    @Test public void serviceAndStackCanAuthorizeTheirActualImageDependency() {
        Instance instance = imageInstance(); when(instance.getServiceId()).thenReturn(2L);
        Service service = mock(Service.class); when(service.getId()).thenReturn(2L);
        when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(8L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        for (Object root : List.of(service, stack)) {
            var dependency = resolver.resolveImageDependency(root, image(), owner);
            assertEquals("1st8", dependency.scope().stackId());
            assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        }
    }
    @Test public void realServiceMapSuppliesMissingDenormalizedParents() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null); when(instance.getServiceId()).thenReturn(null);
        Service service = mapService(instance, 2L, 5L, 8L);
        assertEquals("1st8", resolver.resolveObject("container", instance, owner).stackId());
        for (Object root : List.of(service, stack)) {
            var dependency = resolver.resolveImageDependency(root, image(), owner);
            assertEquals("1st8", dependency.scope().stackId());
            assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        }
        when(service.getAccountId()).thenReturn(6L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(service, image(), owner));
    }
    @Test public void managedMapCannotContradictExplicitStackOrItsOwnAccount() {
        Instance instance = imageInstance(); Service service = mapService(instance, 2L, 5L, 9L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
        when(service.getStackId()).thenReturn(8L);
        var maps = resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true);
        when(maps.getFirst().getAccountId()).thenReturn(6L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(service, image(), owner));
    }
    @Test public void nullAndNonNullManagedServiceStacksRemainAmbiguousInEitherOrder() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null);
        Service first = mapService(instance, 2L, 5L, 8L); when(first.getStackId()).thenReturn(null);
        var firstMaps = resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true);
        mapService(instance, 4L, 5L, 8L);
        var secondMaps = resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true))
                .thenReturn(List.of(firstMaps.getFirst(), secondMaps.getFirst()));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true))
                .thenReturn(List.of(secondMaps.getFirst(), firstMaps.getFirst()));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
    }
    private Service mapService(Instance instance, long id, long account, long stackId) {
        Long instanceId = instance.getId();
        Service service = mock(Service.class); when(service.getId()).thenReturn(id);
        when(service.getAccountId()).thenReturn(account); when(service.getStackId()).thenReturn(stackId);
        when(resolver.objectManager.loadResource(Service.class, id)).thenReturn(service);
        ServiceExposeMap map = mock(ServiceExposeMap.class); when(map.getInstanceId()).thenReturn(instanceId);
        when(map.getServiceId()).thenReturn(id); when(map.getAccountId()).thenReturn(account); when(map.getManaged()).thenReturn(true);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instanceId,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        return service;
    }
    @Test public void imageWithoutPersistedLinkOrUnrelatedRootCannotBorrowScope() {
        Instance instance = imageInstance();
        Instance other = mock(Instance.class); when(other.getId()).thenReturn(99L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(other, image(), owner));
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of());
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(instance, image(), owner));
    }
    @Test public void revokedOwnerOrForeignAdditionalImageReferenceIsDenied() {
        Instance instance = imageInstance(); when(owner.authorizeObject(instance)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveImageDependency(instance, image(), owner)).getCode());
        when(owner.authorizeObject(instance)).thenReturn(instance);
        Instance foreign = mock(Instance.class); when(foreign.getImageId()).thenReturn(7L);
        when(owner.authorizeObject(foreign)).thenReturn(null);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(instance, foreign));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(instance, image(), owner));
    }
    @Test public void realAccountPolicyRequiresLiveOwnedInstanceWithoutHttpContext() {
        Account project = mock(Account.class), principal = mock(Account.class);
        when(project.getId()).thenReturn(5L); when(principal.getId()).thenReturn(10L);
        Policy real = new AccountPolicy(project, principal, java.util.Set.of(), new NoPolicyOptions());
        Instance instance = imageInstance();
        io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
        var dependency = resolver.resolveImageDependency(instance, image(), real);
        assertEquals("1st8", dependency.scope().stackId());
        assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        when(instance.getAccountId()).thenReturn(6L);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveImageDependency(instance, image(), real)).getCode());
        assertNull(io.github.ibuildthecloud.gdapi.context.ApiContext.getContext());
    }
    @Test public void everyActualContainerIdIsPreservedForResourceDenyInsteadOfBecomingImageId() {
        Instance first = imageInstance();
        Instance second = instance(); when(second.getId()).thenReturn(4L);
        when(second.getImageId()).thenReturn(7L); when(second.getStackId()).thenReturn(8L);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null))
                .thenReturn(List.of(first, second));
        var dependency = resolver.resolveImageDependency(stack, image(), owner);
        assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("image", "1img7", "1a5", "1st8"), dependency.scope());
        assertEquals(List.of(
                ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"),
                ApiKeyPolicyEvaluator.Target.stackResource("container", "1i4", "1a5", "1st8")), dependency.instances());
        var deniedContainer = new ApiKeyPolicy.Rule("child-deny", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource("container", "1i4"), java.util.Set.of("upgrade"));
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of(deniedContainer));
        var evaluator = new ApiKeyPolicyEvaluator();
        java.time.Instant now = java.time.Instant.parse("2026-10-08T00:00:00Z");
        assertTrue(evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, "upgrade", true,
                List.of(dependency.scope())), now).allowed());
        var result = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, "upgrade", true,
                dependency.instances()), now);
        assertFalse(result.allowed());
        assertEquals(List.of("child-deny"), result.matchedRuleIds());
    }
    @Test public void standaloneProjectDependencyPreservesRealContainerAndImmutableTargetList() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null);
        var dependency = resolver.resolveImageDependency(instance, image(), owner);
        assertEquals(ApiKeyPolicyEvaluator.Target.projectResource("image", "1img7", "1a5"), dependency.scope());
        var target = ApiKeyPolicyEvaluator.Target.projectResource("container", "1i3", "1a5");
        assertEquals(List.of(target), dependency.instances());
        assertThrows(UnsupportedOperationException.class, () -> dependency.instances().clear());
        var mutable = new java.util.ArrayList<>(List.of(target));
        var copy = new ApiKeyTargetResolver.ImageDependency(dependency.scope(), mutable);
        mutable.clear();
        assertEquals(List.of(target), copy.instances());
    }
    private Image image() { Image image = mock(Image.class); when(image.getId()).thenReturn(7L); return image; }
    private Instance imageInstance() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L); when(instance.getImageId()).thenReturn(7L);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(instance));
        return instance;
    }
    @Before public void setup() {
        resolver = new ApiKeyTargetResolver();
        resolver.objectManager = mock(ObjectManager.class);
        resolver.idFormatter = mock(IdFormatter.class);
        when(resolver.idFormatter.parseId(anyString())).thenAnswer(i -> i.<String>getArgument(0).replaceFirst("^1[a-z]+", ""));
        when(resolver.idFormatter.formatId(anyString(), any())).thenAnswer(i -> {
            String prefix = Map.of("project", "a", "stack", "st", "service", "s", "container", "i", "volume", "v", "image", "img").get(i.<String>getArgument(0));
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
    private Field inputField(boolean create, boolean update, String type) {
        Field field = mock(Field.class);
        when(field.isCreate()).thenReturn(create); when(field.isUpdate()).thenReturn(update);
        when(field.getType()).thenReturn(type);
        return field;
    }
    private ApiRequest inputRequest(String type, String method, Map<String, Field> fields, Map<String, Object> body) {
        ApiRequest request = new ApiRequest(null, null);
        request.setType(type); request.setMethod(method); request.setRequestObject(body);
        SchemaFactory factory = mock(SchemaFactory.class); Schema schema = mock(Schema.class);
        when(schema.getResourceFields()).thenReturn(fields); when(factory.getSchema(type)).thenReturn(schema);
        request.setSchemaFactory(factory); return request;
    }
    @Test public void readOnlyServiceParentCannotGrantStackCreateAuthority() {
        Service service = mock(Service.class);
        when(service.getId()).thenReturn(2L); when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(8L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        ApiRequest request = inputRequest("container", "POST",
                Map.of("serviceId", inputField(false, false, "reference[service]")), Map.of("serviceId", "1s2"));
        var target = resolver.resolve(request, owner).getFirst();
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.PROJECT, target.level());
        assertNull(target.stackId()); assertEquals("1a5", target.projectId());
        verify(resolver.objectManager, never()).loadResource(Service.class, 2L);
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
        ApiRequest request = inputRequest("service", "POST", Map.of(
                "stackId", inputField(true, false, "reference[stack]"),
                "accountId", inputField(true, false, "reference[account]")),
                Map.of("stackId", "1st8", "accountId", "1a6"));
        assertThrows(ClientVisibleException.class, () -> resolver.resolve(request, owner));
        request.setRequestObject(Map.of("stackId", "1st8", "accountId", "1a5"));
        assertEquals("1st8", resolver.resolve(request, owner).getFirst().stackId());
    }
    @Test public void writableDirectStackParentIsStillAValidStandaloneCreate() {
        ApiRequest request = inputRequest("container", "POST",
                Map.of("stackId", inputField(true, false, "reference[stack]")), Map.of("stackId", "1st8"));
        assertEquals("1st8", resolver.resolve(request, owner).getFirst().stackId());
    }
    @Test public void unknownInstanceParentCannotGrantStackCreateAuthority() {
        ApiRequest request = inputRequest("container", "POST", Map.of(), Map.of("instanceId", "1i3"));
        assertNull(resolver.resolve(request, owner).getFirst().stackId());
        verify(resolver.objectManager, never()).loadResource(Instance.class, 3L);
    }
    @Test public void stringWithParentLikeNameIsNotAResourceReference() {
        ApiRequest request = inputRequest("container", "POST",
                Map.of("stackId", inputField(true, true, "string")), Map.of("stackId", "1st8"));
        assertNull(resolver.resolve(request, owner).getFirst().stackId());
        verify(resolver.objectManager, never()).loadResource(Stack.class, 8L);
    }
    @Test public void immutableUpdateParentDoesNotReplacePersistedAncestry() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L);
        when(resolver.objectManager.loadResource("container", 3L)).thenReturn(instance);
        ApiRequest request = inputRequest("container", "PUT",
                Map.of("stackId", inputField(true, false, "reference[stack]")), Map.of("stackId", "1st9"));
        request.setId("1i3");
        var targets = resolver.resolve(request, owner);
        assertEquals(1, targets.size()); assertEquals("1st8", targets.getFirst().stackId());
        verify(resolver.objectManager, never()).loadResource(Stack.class, 9L);
    }
    @Test public void ignoredReadOnlyReferencesDoNotRequireAdditionalAuthority() {
        ApiRequest request = inputRequest("container", "POST",
                Map.of("serviceId", inputField(false, false, "reference[service]")), Map.of("serviceId", "1s2"));
        assertTrue(resolver.references(request, owner).isEmpty());
        verifyNoInteractions(resolver.objectManager);
        request.setMethod("GET");
        assertTrue(resolver.references(request, owner).isEmpty());
    }
    @Test public void collectionActionUsesItsActualInputSchema() {
        ApiRequest request = inputRequest("service", "POST", Map.of(), Map.of("stackId", "1st8"));
        Schema schema = request.getSchemaFactory().getSchema("service");
        Action action = mock(Action.class); when(action.getInput()).thenReturn("actionInput");
        when(schema.getCollectionActions()).thenReturn(Map.of("update", action));
        Schema input = mock(Schema.class);
        Field stackField = inputField(true, false, "reference[stack]");
        when(input.getResourceFields()).thenReturn(Map.of("stackId", stackField));
        when(request.getSchemaFactory().getSchema("actionInput")).thenReturn(input);
        when(resolver.objectManager.loadResource("stack", "8")).thenReturn(stack);
        request.setAction("update");
        assertEquals("1st8", resolver.references(request, owner).getFirst().stackId());
    }
    @Test public void nestedUpdateInputUsesCreateFieldsAsTheValidatorDoes() {
        ApiRequest request = inputRequest("service", "PUT",
                Map.of("launchConfig", inputField(false, true, "launchConfig")),
                Map.of("launchConfig", Map.of("stackId", "1st8")));
        Schema nested = mock(Schema.class);
        Field stackField = inputField(true, false, "reference[stack]");
        when(nested.getResourceFields()).thenReturn(Map.of("stackId", stackField));
        when(request.getSchemaFactory().getSchema("launchConfig")).thenReturn(nested);
        when(resolver.objectManager.loadResource("stack", "8")).thenReturn(stack);
        assertEquals("1st8", resolver.references(request, owner).getFirst().stackId());
    }
    @Test public void putUsesResourceFieldsEvenWithAnActionQueryParameter() {
        ApiRequest request = inputRequest("container", "PUT",
                Map.of("stackId", inputField(true, false, "reference[stack]")), Map.of("stackId", "1st9"));
        request.setAction("update");
        assertTrue(resolver.references(request, owner).isEmpty());
        assertNull(resolver.resolve(request, owner).stream().findFirst().orElse(null));
        verifyNoInteractions(resolver.objectManager);
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
