package org.argos.argos_backend.global.config;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class CorsPropertiesTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void bindsDevelopmentDefaultsAndCustomOriginList() {
        runner.run(context -> assertEquals(List.of("http://localhost:3000", "http://localhost:5173"), context.getBean(CorsProperties.class).allowedOrigins()));
        runner.withPropertyValues("argos.cors.allowed-origins=https://frontend.example.com,https://other.example.com")
            .run(context -> assertEquals(List.of("https://frontend.example.com", "https://other.example.com"), context.getBean(CorsProperties.class).allowedOrigins()));
    }
    @Test void emptySettingDisablesCrossOriginAccess() {
        runner.withPropertyValues("argos.cors.allowed-origins=")
            .run(context -> assertTrue(context.getBean(CorsProperties.class).allowedOrigins().isEmpty()));
    }
    @Test void rejectsWildcardsAndNonOriginUrls() {
        for (String value : List.of("*", "https://*.example.com", "https://example.com/path", "https://user:pass@example.com", "https://example.com/", "null"))
            assertThrows(IllegalArgumentException.class, () -> new CorsProperties(List.of(value)), value);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class Config {}
}
