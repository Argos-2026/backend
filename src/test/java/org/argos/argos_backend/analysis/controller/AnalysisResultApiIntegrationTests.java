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
        assertEquals(first.body(), getPath(job.id() + "/result?section=all").body());
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(first.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        JsonNode result = json.readTree(first.body());
        Set<String> fields = new HashSet<>(); result.propertyNames().forEach(fields::add);
        assertEquals(Set.of("analysisId", "status", "article", "summary", "crossReports", "sourceAssessments", "evidence", "warnings"), fields);
        assertTrue(result.path("article").path("author").isNull());
        assertTrue(result.path("article").path("publishedAt").isNull());
        assertFalse(result.path("article").has("body"));
        assertFalse(result.has("articleUrl"));
        assertFalse(result.has("createdAt"));
        assertFalse(result.has("activeStages"));
        assertEquals("evidence-1", result.path("crossReports").get(0).path("comparisons").get(0).path("evidenceId").asString());
        assertEquals("source-1-1", result.path("sourceAssessments").get(0).path("sources").get(0).path("sourceId").asString());
        assertEquals(6, result.path("sourceAssessments").get(0).path("sources").get(0).path("imvain").size());
        assertEquals("확인한 원문 문장입니다.", result.path("evidence").get(0).path("excerpt").asString());
        verifyNoInteractions(pipeline);
    }

    @Test void sectionResponsesUseDedicatedFieldsAndOnlyReferencedEvidence() throws Exception {
        AnalysisJob job = complete(false);
        JsonNode all = json.readTree(get(job.id().toString()).body());
        var crossResponse = getPath(job.id() + "/result?section=cross-report");
        var sourceResponse = getPath(job.id() + "/result?section=source");
        assertEquals(200, crossResponse.statusCode()); assertEquals(200, sourceResponse.statusCode());
        JsonNode cross = json.readTree(crossResponse.body()), source = json.readTree(sourceResponse.body());
        assertEquals(Set.of("analysisId", "status", "article", "crossReportOverview", "crossReports", "evidence", "warnings"), fields(cross));
        assertEquals(Set.of("analysisId", "status", "article", "sourceAssessmentOverview", "sourceAssessments", "evidence", "warnings"), fields(source));
        assertEquals(all.path("crossReports"), cross.path("crossReports"));
        assertEquals(all.path("sourceAssessments"), source.path("sourceAssessments"));
        assertEquals(all.path("summary").path("crossReportOverview"), cross.path("crossReportOverview"));
        assertEquals(all.path("summary").path("sourceAssessmentOverview"), source.path("sourceAssessmentOverview"));
        assertEquals(1, cross.path("evidence").size()); assertEquals(1, source.path("evidence").size());
        assertEquals("evidence-1", cross.path("evidence").get(0).path("evidenceId").asString());
        assertEquals("evidence-2", source.path("evidence").get(0).path("evidenceId").asString());
        assertEquals("no-store", crossResponse.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-store", sourceResponse.headers().firstValue("Cache-Control").orElseThrow());
        verifyNoInteractions(pipeline);
    }

    @Test void invalidSectionReturnsDocumentedBadRequest() throws Exception {
        AnalysisJob job = complete(false);
        assertError(getPath(job.id() + "/result?section=unknown"), 400, "INVALID_SECTION");
        verifyNoInteractions(pipeline);
    }

    @Test void partialSectionsPreserveGlobalStatusWarningsAndFailedAssessment() throws Exception {
        AnalysisJob job = complete(true);
        JsonNode cross = json.readTree(getPath(job.id() + "/result?section=cross-report").body());
        JsonNode source = json.readTree(getPath(job.id() + "/result?section=source").body());
        assertEquals("PARTIAL_COMPLETED", cross.path("status").asString());
        assertEquals(cross.path("warnings"), source.path("warnings"));
        assertTrue(source.path("sourceAssessments").get(0).path("sources").get(0).path("assessmentFailed").asBoolean());
        assertTrue(source.path("evidence").isEmpty());
    }

    @Test void traceIsReadableInEveryJobStateWithoutReanalysis() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        assertEquals("QUEUED", json.readTree(getPath(job.id() + "/cross-report/trace").body()).path("status").asString());
        job.start();
        var context = new org.argos.argos_backend.analysis.domain.AnalysisContext(clock, now.get().plusSeconds(600), job::stage, job::trace);
        context.trace(t -> t.claim("report-1", "검증 대상", "실제 exact", "실제 expanded"));
        var running = getPath(job.id() + "/cross-report/trace");
        assertEquals(200, running.statusCode());
        JsonNode trace = json.readTree(running.body());
        assertEquals("RUNNING", trace.path("status").asString());
        assertEquals("실제 exact", trace.path("claims").get(0).path("exactQuery").asString());
        assertTrue(trace.path("claims").get(0).path("searches").isEmpty());
        job.fail(new Issue("ANALYSIS_TIMEOUT", Stage.RETRIEVING_EVIDENCE, "제한시간 초과"), List.of(), false);
        JsonNode failed = json.readTree(getPath(job.id() + "/cross-report/trace").body());
        assertEquals("FAILED", failed.path("status").asString());
        assertEquals("ANALYSIS_TIMEOUT", failed.path("error").path("code").asString());
        assertEquals(trace.path("claims"), failed.path("claims"));
        assertError(get(job.id().toString()), 409, "ANALYSIS_FAILED");
        var completed = getPath(complete(false).id() + "/cross-report/trace");
        assertEquals(200, completed.statusCode());
        assertEquals("no-store", completed.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(completed.body().contains("private-internal-url"));
        assertError(getPath("invalid/cross-report/trace"), 400, "INVALID_ANALYSIS_ID");
        assertError(getPath(UUID.randomUUID() + "/cross-report/trace"), 404, "ANALYSIS_NOT_FOUND");
        now.set(now.get().plus(Duration.ofHours(24)));
        assertError(getPath(job.id() + "/cross-report/trace"), 404, "ANALYSIS_NOT_FOUND");
        verifyNoInteractions(pipeline);
    }

    @Test void sectionResultsKeepExistingNotReadyAndFailedErrors() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        for (String section : List.of("all", "cross-report", "source"))
            assertError(getPath(job.id() + "/result?section=" + section), 409, "ANALYSIS_NOT_READY");
        job.start(); job.fail(new Issue("LLM_FAILED", Stage.SELECTING_EVIDENCE, "실패"), List.of(), false);
        for (String section : List.of("all", "cross-report", "source"))
            assertError(getPath(job.id() + "/result?section=" + section), 409, "ANALYSIS_FAILED");
    }

    @Test void partialResultPreservesReportsAndFailedSourceAssessment() throws Exception {
        AnalysisJob job = complete(true);
        var response = get(job.id().toString());
        assertEquals(200, response.statusCode());
        JsonNode result = json.readTree(response.body());
        assertEquals("PARTIAL_COMPLETED", result.path("status").asString());
        assertEquals("MATCHED", result.path("crossReports").get(0).path("verdict").asString());
        assertTrue(result.path("sourceAssessments").get(0).path("sources").get(0).path("assessmentFailed").asBoolean());
        assertEquals("UNKNOWN", result.path("sourceAssessments").get(0).path("sources").get(0)
            .path("imvain").path("verify").path("status").asString());
        assertEquals("SOURCE_ASSESSMENT_FAILED", result.path("warnings").get(0).path("code").asString());
    }

    @Test void noClaimsAndNoSourcesSerializeAsEmptyLists() throws Exception {
        AnalysisJob job = store.create("https://example.com/article");
        job.start(); job.complete(emptyResult(job.id(), Status.COMPLETED));
        JsonNode result = json.readTree(get(job.id().toString()).body());
        for (String field : List.of("crossReports", "evidence", "sourceAssessments", "warnings")) {
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
        Criterion criterion = new Criterion(CriterionStatus.MET, "원문에서 확인했습니다.", List.of("evidence-2"));
        Map<String, Criterion> criteria = new LinkedHashMap<>(); CRITERIA.forEach(key -> criteria.put(key, criterion));
        job.complete(new Result(job.id(), partial ? Status.PARTIAL_COMPLETED : Status.COMPLETED,
            new Article("https://example.com/article", "기사 제목", "언론사", null, null),
            new Summary("종합 코멘트", "교차보도 요약", "정보원 요약", List.of()),
            List.of(new CrossReport("report-1", "검증 대상 주장", ReportVerdict.MATCHED, "다른 언론사도 보도했습니다.",
                1, 0, List.of(new ReportComparison("evidence-1", ReportStance.MATCHES, "내용이 같습니다.")), false)),
            List.of(new SourceAssessment("source-claim-1", "정보원 포함 주장",
                List.of(new ReviewedSource("source-1-1", "정보원", "기관", SourceType.ORGANIZATION,
                    "기사 내 인용", partial ? "평가 실패" : "확인 결과",
                    partial ? CRITERIA.stream().collect(java.util.stream.Collectors.toMap(k -> k,
                        k -> new Criterion(CriterionStatus.UNKNOWN, "평가 실패", List.of()))) : Map.copyOf(criteria), partial)))),
            List.of(new Evidence("evidence-1", "근거 제목", "발행 기관", "https://docs.example.com/a", null, "확인한 원문 문장입니다."),
                new Evidence("evidence-2", "정보원 근거", "발행 기관", "https://docs.example.com/source", null, "정보원 발췌")),
            partial ? List.of(new Issue("SOURCE_ASSESSMENT_FAILED", Stage.ASSESSING_SOURCES, "평가 실패")) : List.of()));
        return job;
    }
    private Set<String> fields(JsonNode object) {
        Set<String> names = new HashSet<>(); object.propertyNames().forEach(names::add); return names;
    }
    private HttpResponse<String> getPath(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
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
