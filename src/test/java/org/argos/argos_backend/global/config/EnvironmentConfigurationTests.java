package org.argos.argos_backend.global.config;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentConfigurationTests {
    @TempDir Path temporary;

    @Test void dotEnvLoadsWithoutAdditionalLibraryAndOsEnvironmentWins() throws Exception {
        Path env = temporary.resolve(".env");
        Files.writeString(env, "OPENAI_API_KEY=file-key\nNAVER_CLIENT_ID=file-id\nNAVER_CLIENT_SECRET=file-secret\nBRAVE_API_KEY=file-brave\n");
        new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace("systemEnvironment",
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of("OPENAI_API_KEY", "os-key"))))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=optional:" + env.toUri() + "[.properties]")
            .withUserConfiguration(AnalysisConfiguration.class)
            .run(context -> {
                assertNull(context.getStartupFailure());
                ArgosProperties properties = context.getBean(ArgosProperties.class);
                assertEquals("os-key", properties.openaiApiKey());
                assertEquals("file-id", properties.naverClientId());
                assertEquals("file-secret", properties.naverClientSecret());
                assertEquals("file-brave", properties.braveApiKey());
                assertTrue(properties.credentialsPresent());
                assertFalse(properties.toString().contains("os-key"));
            });
    }
}
