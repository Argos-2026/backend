package org.argos.argos_backend.external.http;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApacheWebTransportTests {
    @Test void checkedResolverSuppliesConnectionAddressAndResponseDoesNotWaitForDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        // The local fixture is permitted only through this test double, never a production bypass flag.
        SafeUrlPolicy policy = mock(SafeUrlPolicy.class);
        when(policy.validate(anyString())).thenAnswer(i -> URI.create(i.getArgument(0)));
        when(policy.resolve(eq("fixture.example"))).thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        when(policy.resolve(anyString(), anyInt())).thenCallRealMethod();
        when(policy.resolveCanonicalHostname(anyString())).thenAnswer(i -> i.getArgument(0));
        ApacheWebTransport transport = new ApacheWebTransport(policy);
        try {
            long start = System.nanoTime();
            var response = transport.exchange(URI.create("http://fixture.example:" + server.getAddress().getPort() + "/"), "GET", Map.of(), null, Duration.ofSeconds(10), 100);
            assertEquals(200, response.status());
            assertEquals("hello", new String(response.body(), StandardCharsets.UTF_8));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
            verify(policy).resolve("fixture.example");
            assertThrows(ApacheWebTransport.TransportFailure.class, () -> transport.exchange(URI.create("http://fixture.example:" + server.getAddress().getPort() + "/"), "GET", Map.of(), null, Duration.ofSeconds(5), 2));
        } finally { transport.close(); server.stop(0); }
    }
    @Test void slowResponseIsAbortedWithinAbsoluteTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 50; i++) { exchange.getResponseBody().write('a'); exchange.getResponseBody().flush(); Thread.sleep(50); }
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        SafeUrlPolicy policy = mock(SafeUrlPolicy.class);
        when(policy.validate(anyString())).thenAnswer(i -> URI.create(i.getArgument(0)));
        ApacheWebTransport transport = new ApacheWebTransport(policy);
        try {
            long start = System.nanoTime();
            assertThrows(ApacheWebTransport.TransportFailure.class, () -> transport.exchange(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "GET", Map.of(), null, Duration.ofMillis(150), 10000));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        } finally { transport.close(); server.stop(0); executor.shutdownNow(); }
    }
}
