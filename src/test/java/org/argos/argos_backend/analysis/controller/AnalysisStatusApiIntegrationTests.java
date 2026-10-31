package org.argos.argos_backend.analysis.controller;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisJob;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.analysis.service.AnalysisPipeline;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.support.AnalysisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "argos.openai-api-key=test", "argos.naver-client-id=test",
    "argos.naver-client-secret=test", "argos.brave-api-key=test", "argos.workers=1", "argos.queue-capacity=8"
})
class AnalysisStatusApiIntegrationTests {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @MockitoSpyBean AnalysisStore store;
    @MockitoBean AnalysisPipeline pipeline;
    @MockitoBean SafeUrlPolicy urls;
    @MockitoBean Clock clock;
    private final HttpClient http = HttpClient.newHttpClient();
    private AtomicReference<Instant> now;

    @BeforeEach void setup() {
        now = new AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));
        when(clock.instant()).thenAnswer(i -> now.get());
        when(urls.validate(anyString())).thenAnswer(i -> URI.create(i.getArgument(0)));
        when(urls.validateInitial(anyString())).thenAnswer(i -> URI.create(i.getArgument(0)));
    }

    @Test void returnsAllFiveStatesWithOnlyStatusFields() throws Exception {
        for (Status status : Status.values()) {
            AnalysisJob job = store.create("https://example.com/private-article");
            if (status != Status.QUEUED) job.start();
            if (status == Status.RUNNING) job.stage(Stage.RETRIEVING_EVIDENCE);
            if (status == Status.COMPLETED || status == Status.PARTIAL_COMPLETED) job.complete(emptyResult(job.id(), status));
            if (status == Status.FAILED) job.fail(new Issue("ARTICLE_FETCH_FAILED", Stage.FETCHING_ARTICLE, "기사 수집 실패"), List.of(), false);

            var response = get(job.id().toString());
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            JsonNode body = json.readTree(response.body());
            Set<String> fields = new HashSet<>(); body.propertyNames().forEach(fields::add);
            assertEquals(Set.of("analysisId", "status", "activeStages", "expiresAt", "warnings", "error"), fields);
            assertEquals(job.id().toString(), body.path("analysisId").asString());
            assertEquals(status.toString(), body.path("status").asString());
            assertEquals("2026-11-01T00:00:00Z", body.path("expiresAt").asString());
            assertEquals(status == Status.RUNNING ? 1 : 0, body.path("activeStages").size());
            assertEquals(status == Status.PARTIAL_COMPLETED ? 1 : 0, body.path("warnings").size());
            assertEquals(status != Status.FAILED, body.path("error").isNull());
            if (status == Status.FAILED) assertEquals("ARTICLE_FETCH_FAILED", body.path("error").path("code").asString());
            if (status == Status.PARTIAL_COMPLETED) assertEquals("SOURCE_ASSESSMENT_FAILED", body.path("warnings").get(0).path("code").asString());
            assertFalse(response.body().contains("private-article"));
            assertFalse(response.body().contains("private-result"));
            store.remove(job.id());
        }
        verifyNoInteractions(pipeline);
    }

    @Test void rejectsMalformedAndAbbreviatedIds() throws Exception {
        for (String id : List.of("not-a-uuid", "1-1-1-1-1", "a0f02db8c8af48dbb9fd5d6f62303a11", "a0f02db8-c8af-48db-b9fd-5d6f62303a1z")) {
            assertError(get(id), 400, "INVALID_ANALYSIS_ID");
        }
        verifyNoInteractions(pipeline);
    }

    @Test void unknownAndExpiredIdsReturnSameNotFoundError() throws Exception {
        assertError(get(UUID.randomUUID().toString()), 404, "ANALYSIS_NOT_FOUND");
        AnalysisJob expired = store.create("https://example.com/article");
        now.set(expired.expiresAt());
        assertError(get(expired.id().toString()), 404, "ANALYSIS_NOT_FOUND");
        assertTrue(store.find(expired.id()).isEmpty());
    }

    @Test void acceptsUppercaseCanonicalUuid() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        var response = get(job.id().toString().toUpperCase(Locale.ROOT));
        assertEquals(200, response.statusCode());
        assertEquals(job.id().toString(), json.readTree(response.body()).path("analysisId").asString());
    }

    @Test void unexpectedLookupFailureUsesSafeServerError() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("private-storage-secret")).when(store).find(id);
        var response = get(id.toString());
        assertError(response, 500, "INTERNAL_SERVER_ERROR");
        assertFalse(response.body().contains("private-storage-secret"));
    }

    @Test void postIdCanBePolledThroughRunningAndCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pipeline.run(any(), anyString(), any())).thenAnswer(i -> {
            AnalysisContext context = i.getArgument(2);
            context.stage(Stage.VERIFYING_CLAIMS);
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test did not release pipeline");
            return emptyResult(i.getArgument(0), Status.COMPLETED);
        });
        try {
            var accepted = http.send(HttpRequest.newBuilder(base())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"articleUrl\":\"https://example.com/article\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode());
            String id = json.readTree(accepted.body()).path("analysisId").asString();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            JsonNode running = json.readTree(get(id).body());
            assertEquals("RUNNING", running.path("status").asString());
            assertEquals("VERIFYING_CLAIMS", running.path("activeStages").get(0).asString());
            release.countDown();
            JsonNode completed = null;
            for (int i = 0; i < 100; i++) {
                completed = json.readTree(get(id).body());
                if (completed.path("status").asString().equals("COMPLETED")) break;
                Thread.sleep(10);
            }
            assertNotNull(completed);
            assertEquals("COMPLETED", completed.path("status").asString());
            assertTrue(completed.path("activeStages").isEmpty());
            assertTrue(completed.path("error").isNull());
            verify(pipeline, times(1)).run(any(), anyString(), any());
        } finally { release.countDown(); }
    }

    private URI base() { return URI.create("http://localhost:" + port + "/api/v1/analyses"); }
    private HttpResponse<String> get(String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("error").path("code").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
}
