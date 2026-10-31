package org.argos.argos_backend.external.http;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SafeUrlPolicyTests {
    @Test void rejectsPrivateSpecialAndMappedAddresses() throws Exception {
        var policy = new SafeUrlPolicy();
        for (String host : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.1.1",
            "0.0.0.0", "192.0.2.1", "198.18.0.1", "224.0.0.1", "[::1]", "[fc00::1]", "[::ffff:127.0.0.1]", "2130706433", "localhost")) {
            assertThrows(ApiException.class, () -> policy.validate("http://" + host + "/"), host);
        }
        assertTrue(SafeUrlPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(SafeUrlPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
        assertFalse(SafeUrlPolicy.isPublic(InetAddress.getByName("2002:7f00:1::")));
    }
    @Test void resolvesAtConnectionTimeAndRejectsRebindingOrMixedAnswers() throws Exception {
        var calls = new AtomicInteger();
        var publicIp = InetAddress.getByName("8.8.8.8");
        var privateIp = InetAddress.getByName("127.0.0.1");
        var policy = new SafeUrlPolicy(host -> calls.incrementAndGet() == 1 ? new InetAddress[]{publicIp} : new InetAddress[]{privateIp});
        assertNotNull(policy.validateInitial("https://news.example.com/a"));
        assertThrows(ApiException.class, () -> policy.resolve("news.example.com"));
        var mixed = new SafeUrlPolicy(host -> new InetAddress[]{publicIp, privateIp});
        assertThrows(ApiException.class, () -> mixed.resolve("news.example.com"));
    }
    @Test void dnsFailureIsDeferredToBackgroundFetch() {
        var policy = new SafeUrlPolicy(host -> { throw new UnknownHostException(); });
        assertNotNull(policy.validateInitial("https://news.example.com/a"));
    }
    @Test void redirectsCannotReachPrivateAddresses() {
        WebTransport transport = mock(WebTransport.class);
        when(transport.exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt()))
            .thenReturn(new WebTransport.Response(302, "text/html", "http://169.254.169.254/latest/meta-data", new byte[0]));
        var fetcher = new DocumentFetcher(transport, new SafeUrlPolicy(), tools.jackson.databind.json.JsonMapper.builder().build());
        var context = new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(30), s -> {});
        assertThrows(AnalysisFailure.class, () -> fetcher.fetch("https://example.com/a", true, context));
        verify(transport, times(1)).exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt());
    }
    @Test void blocksUnsupportedPortsAndPreservesEncodedArticlePath() {
        var policy = new SafeUrlPolicy();
        assertThrows(ApiException.class, () -> policy.validate("https://example.com:8080/"));
        assertEquals("https://example.com/a%2Fb?key=x%2Fy", policy.validate("https://example.com:443/a%2Fb?key=x%2Fy#fragment").toString());
    }
}
