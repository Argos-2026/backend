package org.argos.argos_backend.global.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("argos.cors")
public record CorsProperties(@DefaultValue({"http://localhost:3000", "http://localhost:5173"}) List<String> allowedOrigins) {
    public CorsProperties {
        allowedOrigins = allowedOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getPort() > 65535 || origin.contains("*"))
                throw new IllegalArgumentException("CORS origins must be exact HTTP/HTTPS origins without a path");
        }
    }
}
