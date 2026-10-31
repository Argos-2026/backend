package org.argos.argos_backend.external.http;

import java.net.URI;
import java.time.*;
import java.util.Map;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Stage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalCallsTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WebTransport transport = mock(WebTransport.class);
    private final ExternalCalls calls = new ExternalCalls(transport, json);
    private final URI uri = URI.create("https://api.openai.com/v1/responses");

    @Test void exhaustedCreditGetsActionableMessageAndNoRetry() {
        stub(429, Map.of("code", "credit_balance_exhausted", "type", "insufficient_quota", "message", "private-secret"));
        AnalysisFailure failure = fail();
        assertTrue(failure.issue(Stage.EXTRACTING_CLAIMS).message().contains("크레딧"));
        assertTrue(failure.issue(Stage.EXTRACTING_CLAIMS).message().contains("HTTP 429"));
        assertEquals("LLM_FAILED", failure.code());
        assertFalse(failure.issue(Stage.EXTRACTING_CLAIMS).message().contains("private-secret"));
        verifyCalls(1);
    }
    @Test void quotaTypeAlsoPreventsRetryWithUnknownCode() {
        stub(429, Map.of("code", "new_quota_code", "type", "insufficient_quota"));
        assertTrue(fail().issue(Stage.EXTRACTING_CLAIMS).message().contains("크레딧"));
        verifyCalls(1);
    }
    @Test void authenticationFailureDoesNotRetryOrExposeRemoteBody() {
        stub(401, Map.of("message", "Incorrect API key: sk-private-secret"));
        String message = fail().issue(Stage.EXTRACTING_CLAIMS).message();
        assertTrue(message.contains("인증"));
        assertFalse(message.contains("sk-private-secret"));
        verifyCalls(1);
    }
    @Test void rateLimitRetriesOnlyOnceAndDiffersFromQuota() {
        stub(429, Map.of("code", "rate_limit_exceeded", "type", "rate_limit_error"));
        String message = fail().issue(Stage.EXTRACTING_CLAIMS).message();
        assertTrue(message.contains("호출 속도"));
        assertFalse(message.contains("크레딧"));
        verifyCalls(2);
    }
    @Test void badModelOrRequestDoesNotRetry() {
        for (int status : new int[]{400, 403, 404}) {
            reset(transport); stub(status, Map.of());
            assertTrue(fail().issue(Stage.EXTRACTING_CLAIMS).message().contains("HTTP " + status));
            verifyCalls(1);
        }
    }
    @Test void serverErrorCanRecoverOnOneRetry() {
        when(transport.exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt()))
            .thenReturn(new WebTransport.Response(503, "text/plain", null, new byte[0]))
            .thenReturn(new WebTransport.Response(200, "application/json", null, new byte[0]));
        assertEquals(200, request().status());
        verifyCalls(2);
    }
    @Test void connectionFailuresRetainBoundedRetry() {
        when(transport.exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt()))
            .thenThrow(new ApacheWebTransport.TransportFailure());
        assertTrue(fail().issue(Stage.EXTRACTING_CLAIMS).message().contains("연결"));
        verifyCalls(2);
    }
    private void stub(int status, Object error) {
        when(transport.exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt()))
            .thenReturn(new WebTransport.Response(status, "application/json", null, json.writeValueAsBytes(Map.of("error", error))));
    }
    private WebTransport.Response request() {
        return calls.request(uri, "POST", Map.of(), new byte[0], Duration.ofSeconds(1), "LLM_FAILED",
            new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(10), s -> {}));
    }
    private AnalysisFailure fail() { return assertThrows(AnalysisFailure.class, this::request); }
    private void verifyCalls(int times) { verify(transport, times(times)).exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt()); }
}
