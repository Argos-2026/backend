package org.argos.argos_backend.analysis.controller;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.argos.argos_backend.analysis.domain.AnalysisJob;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.analysis.service.AnalysisPipeline;
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
    "spring.config.import=", "argos.openai-api-key=", "argos.naver-client-id=", "argos.naver-client-secret=", "argos.brave-api-key=",
    "argos.cors.allowed-origins=http://localhost:3000,http://localhost:5173,https://frontend.example.com"
})
class AnalysisResultApiIntegrationTests {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @MockitoSpyBean AnalysisStore store;
    @MockitoBean AnalysisPipeline pipeline;
    @MockitoBean Clock clock;
    private final HttpClient http = HttpClient.newHttpClient();
    private AtomicReference<Instant> now;

    @BeforeEach void setup() {
        now = new AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));
        when(clock.instant()).thenAnswer(i -> now.get());
    }

    @Test void completedResultMatchesContractWithoutKeysOrReanalysis() throws Exception {
        AnalysisJob job = complete(false);
        var first = get(job.id().toString());
        var second = get(job.id().toString());
        assertEquals(200, first.statusCode());
        assertEquals(first.body(), second.body());
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(first.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        JsonNode result = json.readTree(first.body());
        Set<String> fields = new HashSet<>(); result.propertyNames().forEach(fields::add);
        assertEquals(Set.of("analysisId", "status", "analysisOutcome", "article", "summary", "claims", "evidence", "articleSources", "warnings"), fields);
        assertEquals("ANALYZED", result.path("analysisOutcome").asString());
        assertTrue(result.path("article").path("author").isNull());
        assertTrue(result.path("article").path("publishedAt").isNull());
        assertFalse(result.path("article").has("body"));
        assertFalse(result.has("articleUrl"));
        assertFalse(result.has("createdAt"));
        assertFalse(result.has("activeStages"));
        assertEquals("evidence-1", result.path("claims").get(0).path("evidenceLinks").get(0).path("evidenceId").asString());
        assertEquals("source-1", result.path("claims").get(0).path("articleSourceIds").get(0).asString());
        assertEquals(6, result.path("articleSources").get(0).path("imvain").size());
        assertEquals("확인한 원문 문장입니다.", result.path("evidence").get(0).path("excerpt").asString());
        verifyNoInteractions(pipeline);
    }

    @Test void partialResultPreservesClaimsAndNullFailedSourceAssessment() throws Exception {
        AnalysisJob job = complete(true);
        var response = get(job.id().toString());
        assertEquals(200, response.statusCode());
        JsonNode result = json.readTree(response.body());
        assertEquals("PARTIAL_COMPLETED", result.path("status").asString());
        assertTrue(result.has("articleSources"));
        assertTrue(result.path("articleSources").isNull());
        assertTrue(result.path("summary").has("sourceAssessmentOverview"));
        assertTrue(result.path("summary").path("sourceAssessmentOverview").isNull());
        assertTrue(result.path("claims").get(0).path("articleSourceIds").isEmpty());
        assertEquals("SOURCE_ASSESSMENT_FAILED", result.path("warnings").get(0).path("code").asString());
    }

    @Test void noClaimsAndNoSourcesSerializeAsEmptyLists() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        job.start(); job.complete(emptyResult(job.id(), Status.COMPLETED));
        JsonNode result = json.readTree(get(job.id().toString()).body());
        assertEquals("NO_VERIFIABLE_CLAIMS", result.path("analysisOutcome").asString());
        for (String field : List.of("claims", "evidence", "articleSources", "warnings")) {
            assertTrue(result.path(field).isArray()); assertTrue(result.path(field).isEmpty());
        }
    }

    @Test void queuedAndRunningResultsAreNotReady() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        try {
            assertError(get(job.id().toString()), 409, "ANALYSIS_NOT_READY");
            job.start();
            assertError(get(job.id().toString()), 409, "ANALYSIS_NOT_READY");
        } finally { store.remove(job.id()); }
        verifyNoInteractions(pipeline);
    }

    @Test void failedAnalysisReturnsConflictAndStatusKeepsFailureReason() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        job.fail(new Issue("ARTICLE_FETCH_FAILED", Stage.FETCHING_ARTICLE, "기사 원문을 가져오지 못했습니다."), List.of(), false);
        assertError(get(job.id().toString()), 409, "ANALYSIS_FAILED");
        var status = http.send(HttpRequest.newBuilder(URI.create(base() + "/" + job.id())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode());
        assertEquals("ARTICLE_FETCH_FAILED", json.readTree(status.body()).path("error").path("code").asString());
    }

    @Test void malformedMissingAndExpiredIdsUseExpectedErrors() throws Exception {
        assertError(get("1-1-1-1-1"), 400, "INVALID_ANALYSIS_ID");
        assertError(get("not-a-uuid"), 400, "INVALID_ANALYSIS_ID");
        assertError(get(UUID.randomUUID().toString()), 404, "ANALYSIS_NOT_FOUND");
        AnalysisJob job = complete(false);
        now.set(job.expiresAt());
        assertError(get(job.id().toString()), 404, "ANALYSIS_NOT_FOUND");
    }

    @Test void lookupFailureDoesNotExposeInternalErrors() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("private-secret")).when(store).find(id);
        var response = get(id.toString());
        assertError(response, 500, "INTERNAL_SERVER_ERROR");
        assertFalse(response.body().contains("private-secret"));
    }

    @Test void missingCompletedPayloadReturnsSafeServerError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(Optional.of(new Snapshot(id, "private-internal-url", Status.COMPLETED, List.of(), now.get(), now.get().plusSeconds(3600), List.of(), null, null)))
            .when(store).find(id);
        var response = get(id.toString());
        assertError(response, 500, "INTERNAL_SERVER_ERROR");
        assertFalse(response.body().contains("private-internal-url"));
    }

    @Test void allowsConfiguredBrowserOriginsForGetAndPostPreflight() throws Exception {
        AnalysisJob job = complete(false);
        for (String origin : List.of("http://localhost:3000", "http://localhost:5173", "https://frontend.example.com")) {
            var preflight = http.send(HttpRequest.newBuilder(base()).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, preflight.statusCode());
            assertEquals(origin, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().contains("POST"));
            var response = http.send(HttpRequest.newBuilder(resultUri(job.id().toString())).GET().header("Origin", origin).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
        }
        verifyNoInteractions(pipeline);
    }

    @Test void corsHeadersAreAlsoPresentOnApiErrors() throws Exception {
        var response = http.send(HttpRequest.newBuilder(resultUri("invalid-id")).GET().header("Origin", "http://localhost:3000").build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 400, "INVALID_ANALYSIS_ID");
        assertEquals("http://localhost:3000", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @Test void rejectsUnlistedBrowserOriginsAndDisallowedPreflightMethods() throws Exception {
        var denied = http.send(HttpRequest.newBuilder(resultUri(UUID.randomUUID().toString())).GET()
            .header("Origin", "https://not-allowed.example.com").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        var preflight = http.send(HttpRequest.newBuilder(base()).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "DELETE").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, preflight.statusCode());
        verifyNoInteractions(pipeline);
    }

    private AnalysisJob complete(boolean partial) {
        AnalysisJob job = store.create("https://example.com/article");
        job.start();
        Criterion criterion = new Criterion(CriterionStatus.MET, "원문에서 확인했습니다.", List.of("evidence-1"));
        Map<String, Criterion> criteria = new LinkedHashMap<>(); CRITERIA.forEach(key -> criteria.put(key, criterion));
        job.complete(new Result(job.id(), partial ? Status.PARTIAL_COMPLETED : Status.COMPLETED, "ANALYZED",
            new Article("https://example.com/article", "기사 제목", "언론사", null, null),
            new Summary("종합 코멘트", "판정 요약", partial ? null : "정보원 요약", List.of()),
            List.of(new Claim("claim-1", "검증 대상 주장", Verdict.SUPPORT, "확인한 근거가 뒷받침합니다.", partial ? List.of() : List.of("source-1"), List.of(new EvidenceLink("evidence-1", Stance.SUPPORTS)))),
            List.of(new Evidence("evidence-1", "근거 제목", "발행 기관", "https://docs.example.com/a", null, "확인한 원문 문장입니다.")),
            partial ? null : List.of(new ArticleSource("source-1", "정보원", SourceType.ORGANIZATION, "기사 내 인용", "확인 결과", Map.copyOf(criteria))),
            partial ? List.of(new Issue("SOURCE_ASSESSMENT_FAILED", Stage.ASSESSING_SOURCES, "평가 실패")) : List.of()));
        return job;
    }
    private URI base() { return URI.create("http://localhost:" + port + "/api/v1/analyses"); }
    private URI resultUri(String id) { return URI.create(base() + "/" + id + "/result"); }
    private HttpResponse<String> get(String id) throws Exception {
        return http.send(HttpRequest.newBuilder(resultUri(id)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("error").path("code").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
}
