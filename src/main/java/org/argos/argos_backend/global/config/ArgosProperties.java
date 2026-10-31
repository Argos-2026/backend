package org.argos.argos_backend.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("argos")
public record ArgosProperties(
    @DefaultValue("") String openaiApiKey, @DefaultValue("gpt-5.4-mini") String openaiModel,
    @DefaultValue("") String naverClientId, @DefaultValue("") String naverClientSecret, @DefaultValue("") String braveApiKey,
    @DefaultValue("2") int workers, @DefaultValue("8") int queueCapacity, @DefaultValue("100") int maxJobs,
    @DefaultValue("10m") Duration jobTimeout, @DefaultValue("24h") Duration retention) {

    public ArgosProperties {
        if (workers < 1 || queueCapacity < 1 || maxJobs < 1 || jobTimeout.isNegative() || jobTimeout.isZero()
                || retention.isNegative() || retention.isZero() || jobTimeout.compareTo(retention) >= 0) {
            throw new IllegalArgumentException("Invalid analysis capacity or timeout configuration");
        }
    }

    public boolean credentialsPresent() {
        return !openaiApiKey.isBlank() && !openaiModel.isBlank() && !naverClientId.isBlank()
            && !naverClientSecret.isBlank() && !braveApiKey.isBlank();
    }

    // Configuration can contain credentials: never render record's default toString.
    @Override public String toString() { return "ArgosProperties[credentials=REDACTED]"; }
}
