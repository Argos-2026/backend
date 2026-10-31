package org.argos.argos_backend.external.http;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.springframework.stereotype.Component;

@Component
public class ApacheWebTransport implements WebTransport {
    private final SafeUrlPolicy policy;
    private final CloseableHttpClient client;
    public ApacheWebTransport(SafeUrlPolicy policy) {
        this.policy = policy;
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(policy)
            .setMaxConnTotal(12).setMaxConnPerRoute(4)
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                .setSocketTimeout(Timeout.ofSeconds(15)).build()).build();
        client = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries()
            .disableRedirectHandling().disableCookieManagement().build();
    }
    @Override public Response exchange(URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout, int maxBytes) {
        policy.validate(uri.toString());
        var request = new HttpUriRequestBase(method, uri);
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.max(1, timeout.toMillis())))
            .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1, timeout.toMillis()))).build());
        headers.forEach(request::setHeader);
        request.setHeader("User-Agent", "ARGOS/0.1 (+news-verification)");
        if (body != null) request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        // Socket idle timeouts do not stop a slow streaming response: enforce an absolute deadline too.
        var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        var cancellation = watchdog.schedule(request::cancel, Math.max(1, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            return client.execute(request, response -> {
                String type = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue();
                String location = response.getFirstHeader("Location") == null ? null : response.getFirstHeader("Location").getValue();
                if (response.getEntity() == null) return new Response(response.getCode(), type, location, new byte[0]);
                try (InputStream input = response.getEntity().getContent()) {
                    byte[] bytes = input.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) throw new IOException("Response too large");
                    return new Response(response.getCode(), type, location, bytes);
                }
            });
        } catch (IOException | IllegalStateException e) {
            if (Thread.currentThread().isInterrupted()) throw new AnalysisFailure("ANALYSIS_TIMEOUT", "분석이 취소되었습니다.");
            throw new TransportFailure(e);
        } finally {
            cancellation.cancel(false);
            watchdog.shutdownNow();
        }
    }
    @PreDestroy public void close() throws IOException { client.close(); }
    public static class TransportFailure extends RuntimeException {
        public TransportFailure() { super("External HTTP request failed"); }
        public TransportFailure(Throwable cause) { super("External HTTP request failed", cause); }
    }
}
