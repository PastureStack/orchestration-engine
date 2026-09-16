package io.cattle.platform.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.spring.resource.SpringResourceLoader;
import io.github.ibuildthecloud.gdapi.annotation.Type;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.util.HashSet;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

public class TypesConfigTest {

    private static final String EXTERNAL_ID_TYPES = "auth.service.external.id.types";

    @After
    public void clearExternalIdTypes() {
        if (ConfigurationManager.getConfigInstance().containsKey(EXTERNAL_ID_TYPES)) {
            ConfigurationManager.getConfigInstance().clearProperty(EXTERNAL_ID_TYPES);
        }
    }

    @Test
    public void coreSchemaWaitsForConfigurationAndPublishesOidcProjectMemberTypes() {
        clearExternalIdTypes();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TestDependencies.class, TypesConfig.class);
            context.refresh();

            SchemaFactoryImpl factory = context.getBean("CoreSchemaFactory", SchemaFactoryImpl.class);
            factory.registerSchema(ProjectMemberSchema.class);
            Schema schema = factory.parseSchema("projectMember");
            List<String> options = schema.getResourceFields().get("externalIdType").getOptions();

            assertTrue(options.contains("oidc_user"));
            assertTrue(options.contains("oidc_group"));
            assertEquals(options.size(), new HashSet<String>(options).size());
        }
    }

    @Configuration
    static class TestDependencies {

        @Bean(name = "ArchaiusStartup")
        @Lazy
        Object ArchaiusStartup() {
            ConfigurationManager.getConfigInstance().setProperty(EXTERNAL_ID_TYPES,
                    "oidc_user,oidc_group,oidc_user");
            return new Object();
        }

        @Bean
        SpringResourceLoader ResourceLoader() {
            return new SpringResourceLoader();
        }

        @Bean
        JacksonJsonMapper CoreJsonMapper() {
            return new JacksonJsonMapper();
        }

        @Bean
        JacksonMapper JacksonMapper() {
            return new JacksonMapper();
        }
    }

    @Type(name = "projectMember")
    public static class ProjectMemberSchema {
        private String externalIdType;

        public String getExternalIdType() {
            return externalIdType;
        }

        public void setExternalIdType(String externalIdType) {
            this.externalIdType = externalIdType;
        }
    }
}
