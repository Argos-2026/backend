package org.argos.argos_backend.global.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ArgosProperties.class)
public class AnalysisConfiguration {
    @Bean Clock analysisClock() { return Clock.systemUTC(); }
}
