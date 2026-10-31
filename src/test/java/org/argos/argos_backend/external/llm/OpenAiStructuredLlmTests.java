package org.argos.argos_backend.external.llm;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.external.http.ExternalCalls;
import org.argos.argos_backend.external.http.WebTransport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.external.llm.LlmModels.*;
import static org.argos.argos_backend.support.AnalysisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiStructuredLlmTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExternalCalls calls = mock(ExternalCalls.class);
    private final OpenAiStructuredLlm llm = new OpenAiStructuredLlm(
        calls, properties(100, Duration.ofMinutes(10), true), json);

    @Test void invalidStructuredResponseIsRetriedOnce() {
        AtomicInteger attempts = new AtomicInteger();
        when(calls.request(any(), eq("POST"), anyMap(), any(), any(), eq("LLM_FAILED"), any()))
            .thenAnswer(invocation -> response(attempts.incrementAndGet() == 1 ? "{broken"
                : json.writeValueAsString(new Extraction(List.of(), List.of()))));
        Extraction result = llm.generate("extract", "{}", Extraction.class, value -> {
            assertNotNull(value.crossReportClaims());
            assertNotNull(value.sourceClaims());
        }, context());
        assertTrue(result.crossReportClaims().isEmpty());
        assertEquals(2, attempts.get());
    }

    @Test void invalidStructuredResponseAfterRetryFailsSafely() {
        when(calls.request(any(), eq("POST"), anyMap(), any(), any(), eq("LLM_FAILED"), any()))
            .thenReturn(response("{broken"));
        AnalysisFailure failure = assertThrows(AnalysisFailure.class,
            () -> llm.generate("extract", "{}", Extraction.class, value -> {}, context()));
        assertEquals("LLM_FAILED", failure.code());
        verify(calls, times(2)).request(any(), eq("POST"), anyMap(), any(), any(), eq("LLM_FAILED"), any());
    }

    private WebTransport.Response response(String content) {
        return new WebTransport.Response(200, "application/json", null,
            json.writeValueAsBytes(Map.of("status", "completed", "output",
                List.of(Map.of("content", List.of(Map.of("type", "output_text", "text", content)))))));
    }
    private AnalysisContext context() {
        return new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(60), ignored -> {});
    }
}
