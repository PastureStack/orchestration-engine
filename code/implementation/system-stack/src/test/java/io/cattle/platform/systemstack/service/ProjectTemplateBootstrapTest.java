package io.cattle.platform.systemstack.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.ProjectTemplate;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ProjectTemplateRecord;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.sample.data.SampleDataStartupV3;
import io.cattle.platform.systemstack.catalog.CatalogService;
import io.cattle.platform.systemstack.process.ProjecttemplateCreate;
import io.cattle.platform.systemstack.process.SystemStackTrigger;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.netflix.config.ConfigurationManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class ProjectTemplateBootstrapTest {
    private static final String DEFAULT_NAME = "project.template.default.name";
    private static final List<String> SETTINGS = Arrays.asList(DEFAULT_NAME, "sample.setup",
            "account.version", "catalog.execute", "project.template.defaults");
    private final Map<String, Object> previousSettings = new HashMap<>();
    private byte[] catalogProject;
    private Map<?, ?> project;

    @Before
    public void configurePackagedDefaultAndRealCatalogProject() throws Exception {
        for (String key : SETTINGS) {
            previousSettings.put(key, ConfigurationManager.getConfigInstance().getProperty(key));
        }
        Properties defaults = new Properties();
        try (InputStream stream = Files.newInputStream(Path.of("../../packaging/app-config/src/main/resources/"
                + "META-INF/cattle/defaults/defaults.properties"))) {
            defaults.load(stream);
        }
        Properties apiDefaults = new Properties();
        try (InputStream stream = Files.newInputStream(Path.of("../../packaging/app-config/src/main/resources/"
                + "META-INF/cattle/api-server/defaults.properties"))) {
            apiDefaults.load(stream);
        }
        try (InputStream stream = getClass().getResourceAsStream("/catalog/native/project.yml")) {
            catalogProject = stream.readAllBytes();
        }
        project = Map.class.cast(new Yaml().load(new String(catalogProject, StandardCharsets.UTF_8)));
        ConfigurationManager.getConfigInstance().setProperty(DEFAULT_NAME, defaults.getProperty(DEFAULT_NAME));
        ConfigurationManager.getConfigInstance().setProperty("sample.setup", false);
        ConfigurationManager.getConfigInstance().setProperty("account.version", apiDefaults.getProperty("account.version"));
        ConfigurationManager.getConfigInstance().setProperty("catalog.execute", true);
        ConfigurationManager.getConfigInstance().setProperty("project.template.defaults", true);
    }

    @After
    public void restoreSettings() {
        for (String key : SETTINGS) {
            Object value = previousSettings.get(key);
            if (value == null) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            } else {
                ConfigurationManager.getConfigInstance().setProperty(key, value);
            }
        }
    }

    @Test
    public void packagedDefaultMatchesPinnedCatalogProjectAndItsFourStacks() throws Exception {
        // Exact project-templates/native/0/project.yml from catalog-templates
        // b6b658888fce50d3ec217eb4eba0f26ab0113baf, the Server 530 pinned source.
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        assertEquals("4430246526cc6b858b939110e008480c213f0f41f660df25a02f4cf4cdd260c5",
                HexFormat.of().formatHex(sha256.digest(catalogProject)));
        assertEquals(project.get("name"), CatalogService.DEFAULT_TEMPLATE.get());
        List<?> stacks = List.class.cast(project.get("stacks"));
        assertEquals(4, stacks.size());
        List<String> ids = stacks.stream().map(Map.class::cast)
                .map(stack -> String.class.cast(stack.get("templateId"))).toList();
        assertEquals(Arrays.asList("pasturestack:infra*network-services", "pasturestack:infra*ipsec-overlay",
                "pasturestack:infra*resource-scheduler", "pasturestack:infra*healthcheck"), ids);
    }

    @Test
    public void freshTemplateCreationPersistsBindingBeforeTriggerAndIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.create();
        fixture.create();
        assertEquals(fixture.template.getId(), fixture.account.getProjectTemplateId());
        assertEquals(1, fixture.binds.get());
        assertEquals(1, fixture.triggers.get());
    }

    @Test
    public void reloadRepairsInstalledTemplateWithoutRecreatingItAndIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loader.run();
        fixture.loader.run();
        assertEquals(fixture.template.getId(), fixture.account.getProjectTemplateId());
        assertEquals(1, fixture.binds.get());
        assertEquals(1, fixture.triggers.get());
        assertEquals(2, fixture.catalogReads.get());
    }

    @Test
    public void creationAndReloadShareTheTemplateLock() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> { await(start); fixture.create(); });
            Future<?> reload = executor.submit(() -> { await(start); fixture.loader.run(); });
            start.countDown();
            creation.get(10, TimeUnit.SECONDS);
            reload.get(10, TimeUnit.SECONDS);
            assertEquals(1, fixture.binds.get());
            assertEquals(1, fixture.triggers.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void reloadPreservesExistingTemplateBinding() throws Exception {
        Fixture fixture = new Fixture();
        fixture.account.setProjectTemplateId(99L);
        fixture.loader.run();
        fixture.create();
        assertEquals(Long.valueOf(99L), fixture.account.getProjectTemplateId());
        fixture.assertUntouched();
    }

    @Test
    public void explicitCustomDefaultSettingDoesNotFallBackToNative() throws Exception {
        Fixture fixture = new Fixture();
        ConfigurationManager.getConfigInstance().setProperty(DEFAULT_NAME, "Cattle");
        fixture.loader.run();
        fixture.create();
        assertEquals("Cattle", CatalogService.DEFAULT_TEMPLATE.get());
        assertNull(fixture.account.getProjectTemplateId());
        fixture.assertUntouched();
    }

    @Test
    public void explicitlySelectedCustomTemplateIsRespected() throws Exception {
        Fixture fixture = new Fixture();
        ConfigurationManager.getConfigInstance().setProperty(DEFAULT_NAME, "Custom Native");
        fixture.template.setName("Custom Native");
        fixture.loader.run();
        assertEquals(fixture.template.getId(), fixture.account.getProjectTemplateId());
        assertEquals("Custom Native", CatalogService.DEFAULT_TEMPLATE.get());
        assertEquals(1, fixture.binds.get());
    }

    @Test
    public void nonDefaultProjectIsNotBound() throws Exception {
        Fixture fixture = new Fixture();
        fixture.account.setUuid("userProject");
        fixture.account.setName("Default");
        fixture.loader.run();
        fixture.create();
        fixture.assertUntouched();
    }

    @Test
    public void privateOrRemovedTemplateIsNotBound() throws Exception {
        Fixture privateTemplate = new Fixture();
        privateTemplate.template.setIsPublic(false);
        privateTemplate.loader.run();
        privateTemplate.create();
        privateTemplate.assertUntouched();
        Fixture removedTemplate = new Fixture();
        removedTemplate.template.setRemoved(new Date());
        removedTemplate.loader.run();
        removedTemplate.create();
        removedTemplate.assertUntouched();
    }

    @Test
    public void removedWrongKindOrOldVersionDefaultIsNotBound() throws Exception {
        Fixture removed = new Fixture();
        removed.account.setRemoved(new Date());
        removed.loader.run();
        removed.assertUntouched();
        Fixture wrongKind = new Fixture();
        wrongKind.account.setKind("user");
        wrongKind.loader.run();
        wrongKind.assertUntouched();
        Fixture oldVersion = new Fixture();
        oldVersion.account.setVersion("legacy");
        oldVersion.loader.run();
        oldVersion.assertUntouched();
    }

    private final class Fixture {
        final AccountRecord account = new AccountRecord();
        final ProjectTemplateRecord template = new ProjectTemplateRecord();
        final AtomicInteger binds = new AtomicInteger();
        final AtomicInteger triggers = new AtomicInteger();
        final AtomicInteger catalogReads = new AtomicInteger();
        final ProjecttemplateCreate handler = new ProjecttemplateCreate();
        final ProjectTemplateService loader = new ProjectTemplateService();

        Fixture() throws Exception {
            account.setId(7L);
            account.setUuid(ProjectConstants.DEFAULT_PROJECT_UUID);
            account.setKind("project");
            account.setVersion(ConfigurationManager.getConfigInstance().getString("account.version"));
            template.setId(20L);
            template.setName(String.class.cast(project.get("name")));
            template.setIsPublic(true);
            template.setState("active");
            template.setExternalId("catalog://pasturestack:project*native:0");
            LockManagerImpl locks = new LockManagerImpl();
            locks.setLockProvider(new InMemoryLockProvider());
            ObjectManager objects = ObjectManager.class.cast(Proxy.newProxyInstance(
                    ObjectManager.class.getClassLoader(), new Class<?>[] {ObjectManager.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                        case "find":
                            assertSame(ProjectTemplate.class, args[0]);
                            return List.<ProjectTemplate>of(template);
                        case "findAny":
                            assertSame(Account.class, args[0]);
                            assertEquals(ProjectConstants.DEFAULT_PROJECT_UUID, ((Object[]) args[2])[0]);
                            return account;
                        case "setFields":
                            assertSame(account, args[0]);
                            assertNull("Existing bindings must never be overwritten", account.getProjectTemplateId());
                            account.setProjectTemplateId(Long.class.cast(((Object[]) args[2])[0]));
                            binds.incrementAndGet();
                            return account;
                        default:
                            throw new AssertionError("Unexpected object mutation: " + method.getName());
                        }
                    }));
            handler.setObjectManager(objects);
            inject(handler, "sampleDataStartupV3", new SampleDataStartupV3());
            inject(handler, "lockManager", locks);
            inject(handler, "systemStackTrigger", new SystemStackTrigger() {
                @Override
                public void trigger(Long accountId) {
                    assertEquals(account.getId(), accountId);
                    assertEquals("The binding must be persisted before triggering", template.getId(),
                            account.getProjectTemplateId());
                    triggers.incrementAndGet();
                }
            });
            loader.lockManager = locks;
            loader.objectManager = objects;
            loader.projectTemplateCreate = handler;
            loader.catalogService = CatalogService.class.cast(Proxy.newProxyInstance(
                    CatalogService.class.getClassLoader(), new Class<?>[] {CatalogService.class},
                    (proxy, method, args) -> {
                        assertEquals("getTemplates", method.getName());
                        catalogReads.incrementAndGet();
                        Map<Object, Object> data = new HashMap<>();
                        project.forEach(data::put);
                        Map<String, Map<Object, Object>> templates = new HashMap<>();
                        templates.put(template.getExternalId(), data);
                        return templates;
                    }));
            // resourceDao is deliberately absent: an installed template must not be recreated.
        }

        void create() {
            ProcessState state = ProcessState.class.cast(Proxy.newProxyInstance(
                    ProcessState.class.getClassLoader(), new Class<?>[] {ProcessState.class},
                    (proxy, method, args) -> {
                        assertEquals("getResource", method.getName());
                        return template;
                    }));
            handler.handle(state, null);
        }

        void assertUntouched() {
            assertEquals(0, binds.get());
            assertEquals(0, triggers.get());
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
