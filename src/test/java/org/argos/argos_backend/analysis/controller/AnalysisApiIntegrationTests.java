package org.argos.argos_backend.analysis.controller;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.external.http.WebTransport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.external.llm.LlmModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "argos.openai-api-key=test-openai", "argos.naver-client-id=test-naver", "argos.naver-client-secret=test-secret",
    "argos.brave-api-key=test-brave", "spring.config.import=", "argos.max-jobs=100"
})
class AnalysisApiIntegrationTests {
    private static final String URL = "https://news.example.com/article";
    private static final String DOC = "https://docs.example.com/budget";
    private static final String DOC2 = "https://other.example.com/revised";
    private static final String QUOTE2 = "수정 예산안의 감소율은 20%이며 이전에 발표된 수치는 최종 의결 내용과 다릅니다.";
    private static final String CITATION = "새빛시 예산안은 내년도 예산이 12% 감소한다고 밝혔다.";
    private static final String QUOTE = "전체 복지 예산은 전년 대비 12% 감소한다.";
    private static final String SPEECH = "김민수는 예산이 12% 감소한다고 말했다.";
    private static final String OTHER_SPEECH = "이영희는 예산이 12% 감소한다고 말했다.";
    @LocalServerPort int port;
    @Autowired AnalysisStore store;
    @Autowired JsonMapper json;
    @MockitoBean WebTransport transport;
    private final HttpClient http = HttpClient.newHttpClient();
    private String scenario;
    private AtomicInteger verificationCalls;

    @BeforeEach void setup() {
        scenario = "success";
        verificationCalls = new AtomicInteger();
        when(transport.exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getHost().equals("news.example.com")) return html("""
                <html><head><meta property="og:title" content="내년도 예산 발표">
                <meta property="og:site_name" content="가상일보"><meta name="author" content="홍길동"></head>
                <body><article>%s %s</article></body></html>
                """.formatted(CITATION + (isAttribution() ? " " + SPEECH : ""), "시는 이번 예산안의 주요 항목과 편성 경위를 설명하며 시민들에게 관련 세부 자료를 공개했다. 복지 사업별 조정 내용은 공식 문서를 통해 확인할 수 있으며 기사에서는 내년도 예산 변화에 관한 주요 내용을 소개한다."));
            if (uri.getHost().equals("docs.example.com")) return html("<html><head><title>공식 예산안</title></head><body><main>" + QUOTE
                + (scenario.equals("attribution-grounded") ? " " + SPEECH : scenario.equals("attribution-other-speaker") ? " " + OTHER_SPEECH : "")
                + " 예산 편성 기관은 새빛시청이며 사업별 세부 내역을 함께 공개한다.</main></body></html>");
            if (uri.getHost().equals("other.example.com")) return html("<html><head><title>수정 예산안</title></head><body><main>" + QUOTE2 + "</main></body></html>");
            if (uri.getHost().contains("ntruss") || uri.getHost().contains("brave")) {
                if (scenario.equals("search-failed") || (scenario.equals("search-partial") && uri.getHost().contains("ntruss"))) return response(503, Map.of());
                List<?> items = scenario.equals("no-evidence") ? List.of() : scenario.equals("conflict")
                    ? List.of(Map.of("url", DOC, "originallink", DOC, "title", "공식 예산안"), Map.of("url", DOC2, "originallink", DOC2, "title", "수정 예산안"))
                    : List.of(Map.of("url", DOC, "originallink", DOC, "title", "공식 예산안"));
                return response(200, uri.getHost().contains("ntruss") ? Map.of("items", items) : Map.of("web", Map.of("results", items)));
            }
            if (uri.getHost().equals("api.openai.com")) {
                if (scenario.equals("quota")) return response(429, Map.of("error", Map.of("code", "credit_balance_exhausted", "type", "insufficient_quota", "message", "private-provider-detail")));
                JsonNode request = json.readTree((byte[]) invocation.getArgument(3));
                String schema = request.path("text").path("format").path("name").asString();
                Object output = switch (schema) {
                    case "Extraction" -> scenario.equals("no-claims") ? new Extraction(List.of(), List.of(), false) : extraction();
                    case "Selection" -> new Selection(List.of(new SelectedClaim("claim-1", scenario.equals("no-evidence") ? List.of() : scenario.equals("conflict")
                        ? List.of(new Quote("evidence-1", QUOTE), new Quote("evidence-2", QUOTE2))
                        : List.of(new Quote("evidence-1", scenario.equals("hallucinated") ? "원문에 없는 인용문" : QUOTE)))));
                    case "Verification" -> {
                        verificationCalls.incrementAndGet();
                        if (isAttribution()) yield new Verification(List.of(new VerifiedClaim("claim-1", Verdict.SUPPORT,
                            scenario.equals("attribution-grounded") ? "외부 보도 원문에서 김민수의 해당 발언을 확인했습니다."
                                : "예산 감소는 확인되지만 김민수의 발언 자체는 확인하지 못했습니다.", List.of(new EvidenceLink("evidence-1", Stance.SUPPORTS)), true,
                            scenario.equals("attribution-underlying-only") ? List.of()
                                : List.of(new Quote("evidence-1", scenario.equals("attribution-other-speaker") ? OTHER_SPEECH : SPEECH)))));
                        yield new Verification(List.of(new VerifiedClaim("claim-1", scenario.equals("conflict") ? Verdict.CONFLICT : scenario.equals("refute") ? Verdict.REFUTE : Verdict.SUPPORT,
                            "공식 자료를 대조한 결과입니다.", scenario.equals("conflict") ? List.of(new EvidenceLink("evidence-1", Stance.SUPPORTS), new EvidenceLink("evidence-2", Stance.REFUTES))
                            : List.of(new EvidenceLink("evidence-1", scenario.equals("refute") ? Stance.REFUTES : scenario.equals("ungrounded-verdict") ? Stance.CONTEXT : Stance.SUPPORTS)), true, List.of())));
                    }
                    case "Assessment" -> assessment();
                    case "OverallSummary" -> new OverallSummary("확보한 근거를 확인하세요.", "주장별 검증 결과입니다.", "정보원을 개별 평가했습니다.", List.of());
                    default -> throw new AssertionError("Unexpected schema " + schema);
                };
                if ((scenario.equals("source-failed") && schema.equals("Assessment")) || (scenario.equals("summary-failed") && schema.equals("OverallSummary"))) return response(401, Map.of());
                return response(200, Map.of("status", "completed", "usage", Map.of("input_tokens", 100, "output_tokens", 50),
                    "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", json.writeValueAsString(output)))))));
            }
            throw new AssertionError("Unexpected external host " + uri.getHost());
        });
    }

    @Test void postRunsRealPipelineAndStoresGroundedResult() throws Exception {
        var response = post("{\"articleUrl\":\"" + URL + "\"}", "application/json");
        assertEquals(202, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode accepted = json.readTree(response.body());
        assertEquals("QUEUED", accepted.path("status").asString());
        assertEquals(Duration.ofHours(24), Duration.between(Instant.parse(accepted.path("createdAt").asString()), Instant.parse(accepted.path("expiresAt").asString())));
        Snapshot job = await(accepted);
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(Verdict.SUPPORT, job.result().claims().getFirst().verdict());
        assertEquals(List.of("source-1"), job.result().claims().getFirst().articleSourceIds());
        assertTrue(job.result().evidence().getFirst().excerpt().contains(QUOTE));
        assertEquals(6, job.result().articleSources().getFirst().imvain().size());
        assertEquals(1, verificationCalls.get());
        assertTrue(job.activeStages().isEmpty());
        assertNull(job.error());
        // All three public endpoints run against the real pipeline and fake remote services.
        clearInvocations(transport);
        var first = get(accepted.path("analysisId").asString() + "/result");
        var second = get(accepted.path("analysisId").asString() + "/result");
        assertEquals(200, first.statusCode());
        assertEquals(first.body(), second.body());
        JsonNode resultBody = json.readTree(first.body());
        assertEquals("SUPPORT", resultBody.path("claims").get(0).path("verdict").asString());
        assertTrue(resultBody.path("evidence").get(0).path("excerpt").asString().contains(QUOTE));
        assertEquals(6, resultBody.path("articleSources").get(0).path("imvain").size());
        assertFalse(resultBody.path("article").has("body"));
        assertFalse(first.body().contains("test-secret"));
        verifyNoInteractions(transport);
    }

    @Test void validatesRequestShapeAndProtocol() throws Exception {
        assertError("{}", "application/json", 400, "INVALID_URL");
        assertError("{\"articleUrl\":null}", "application/json", 400, "INVALID_URL");
        assertError("{\"articleUrl\":12}", "application/json", 400, "INVALID_REQUEST");
        assertError("[]", "application/json", 400, "INVALID_REQUEST");
        assertError("broken", "application/json", 400, "INVALID_REQUEST");
        assertError("", "application/json", 400, "INVALID_REQUEST");
        assertError("{\"articleUrl\":\"hello\"}", "application/json", 400, "INVALID_URL");
        assertError("{\"articleUrl\":\"ftp://example.com/a\"}", "application/json", 422, "UNSUPPORTED_URL");
        assertError("{\"articleUrl\":\"http://127.0.0.1/a\"}", "application/json", 422, "UNSUPPORTED_URL");
        assertError("{\"articleUrl\":\"https://user:password@example.com\"}", "application/json", 422, "UNSUPPORTED_URL");
        assertError("{}", "text/plain", 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test void noClaimsIsNormalCompletion() throws Exception {
        scenario = "no-claims";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals("NO_VERIFIABLE_CLAIMS", job.result().analysisOutcome());
        assertTrue(job.result().claims().isEmpty());
        assertTrue(job.result().articleSources().isEmpty());
    }
    @Test void exhaustedOpenAiCreditIsVisibleInStatusAndIsNotRetried() throws Exception {
        scenario = "quota";
        Snapshot job = submitAndWait();
        assertEquals(Status.FAILED, job.status());
        assertEquals("LLM_FAILED", job.error().code());
        assertEquals(Stage.EXTRACTING_CLAIMS, job.error().stage());
        assertTrue(job.error().message().contains("크레딧"));
        assertFalse(job.error().message().contains("private-provider-detail"));
        verify(transport, times(1)).exchange(argThat(u -> u.getHost().equals("api.openai.com")), anyString(), anyMap(), nullable(byte[].class), any(), anyInt());
    }
    @Test void noEvidenceDoesNotInvokeVerdictModelOrInventSupport() throws Exception {
        scenario = "no-evidence";
        // Empty candidate lists must be accepted by the selection response as well.
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(Verdict.INSUFFICIENT, job.result().claims().getFirst().verdict());
        assertEquals(0, verificationCalls.get());
    }
    @Test void noArticleSourcesReturnsEmptyArrayRatherThanFailedAssessment() throws Exception {
        scenario = "no-sources";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertTrue(job.result().articleSources().isEmpty());
        assertTrue(job.result().claims().getFirst().articleSourceIds().isEmpty());
        assertTrue(job.result().warnings().isEmpty());
    }
    @Test void allSearchCallsFailAsFailureRatherThanInsufficient() throws Exception {
        scenario = "search-failed";
        Snapshot job = submitAndWait();
        assertEquals(Status.FAILED, job.status());
        assertEquals("SEARCH_FAILED", job.error().code());
        assertNull(job.result());
    }
    @Test void oneProviderFailureIsPartialCompletion() throws Exception {
        scenario = "search-partial";
        Snapshot job = submitAndWait();
        assertEquals(Status.PARTIAL_COMPLETED, job.status());
        assertEquals("SEARCH_FAILED", job.warnings().getFirst().code());
        assertEquals(Verdict.SUPPORT, job.result().claims().getFirst().verdict());
    }
    @Test void sourceFailurePreservesClaimsAndClearsSourceReferences() throws Exception {
        scenario = "source-failed";
        Snapshot job = submitAndWait();
        assertEquals(Status.PARTIAL_COMPLETED, job.status());
        assertNull(job.result().articleSources());
        assertNull(job.result().summary().sourceAssessmentOverview());
        assertTrue(job.result().claims().getFirst().articleSourceIds().isEmpty());
        assertEquals("SOURCE_ASSESSMENT_FAILED", job.warnings().getFirst().code());
    }
    @Test void invalidQuoteFailsAfterBoundedRegeneration() throws Exception {
        scenario = "hallucinated";
        Snapshot job = submitAndWait();
        assertEquals(Status.FAILED, job.status());
        assertEquals("LLM_FAILED", job.error().code());
        assertEquals(Stage.SELECTING_EVIDENCE, job.error().stage());
    }
    @Test void summaryFailureUsesDeterministicFallback() throws Exception {
        scenario = "summary-failed";
        Snapshot job = submitAndWait();
        assertEquals(Status.PARTIAL_COMPLETED, job.status());
        assertTrue(job.result().summary().factCheckOverview().contains("SUPPORT"));
        assertEquals(Stage.AGGREGATING_RESULT, job.warnings().getFirst().stage());
    }
    @Test void conflictingEvidenceRetainsBothOpposingLinks() throws Exception {
        scenario = "conflict";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(Verdict.CONFLICT, job.result().claims().getFirst().verdict());
        assertEquals(2, job.result().claims().getFirst().evidenceLinks().size());
        assertEquals(2, job.result().evidence().size());
    }
    @Test void refutationRequiresRefutingEvidence() throws Exception {
        scenario = "refute";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(Verdict.REFUTE, job.result().claims().getFirst().verdict());
        assertEquals(Stance.REFUTES, job.result().claims().getFirst().evidenceLinks().getFirst().stance());
    }
    @Test void unsupportedDefinitiveVerdictIsRejected() throws Exception {
        scenario = "ungrounded-verdict";
        Snapshot job = submitAndWait();
        assertEquals(Status.FAILED, job.status());
        assertEquals("LLM_FAILED", job.error().code());
        assertEquals(Stage.VERIFYING_CLAIMS, job.error().stage());
    }
    @Test void anonymousSourceIsNotSearchedOrRatedAllNotMet() throws Exception {
        scenario = "anonymous";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        var imvain = job.result().articleSources().getFirst().imvain();
        assertEquals(CriterionStatus.NOT_MET, imvain.get("named").status());
        assertEquals(CriterionStatus.UNKNOWN, imvain.get("authoritative").status());
        verify(transport, times(1)).exchange(argThat(u -> u.getHost().contains("brave")), anyString(), anyMap(), nullable(byte[].class), any(), anyInt());
    }

    @Test void attributedClaimWithOnlyUnderlyingFactsBecomesInsufficient() throws Exception {
        scenario = "attribution-underlying-only";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        Claim claim = job.result().claims().getFirst();
        assertEquals(Verdict.INSUFFICIENT, claim.verdict());
        assertEquals(Stance.CONTEXT, claim.evidenceLinks().getFirst().stance());
        assertTrue(claim.reason().contains("발언 여부"));
    }

    @Test void attributedClaimWithActualSpeakerQuoteIsSupportedAndReturnsThatQuote() throws Exception {
        scenario = "attribution-grounded";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(Verdict.SUPPORT, job.result().claims().getFirst().verdict());
        assertTrue(job.result().evidence().getFirst().excerpt().contains(SPEECH));
    }

    @Test void quoteFromDifferentSpeakerCannotSupportAttributedClaim() throws Exception {
        scenario = "attribution-other-speaker";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(Verdict.INSUFFICIENT, job.result().claims().getFirst().verdict());
    }

    @Test void fabricatedAttributionQuoteIsRejectedRatherThanUsedAsEvidence() throws Exception {
        scenario = "attribution-fabricated-quote";
        Snapshot job = submitAndWait();
        assertEquals(Status.FAILED, job.status());
        assertEquals("LLM_FAILED", job.error().code());
        assertEquals(Stage.VERIFYING_CLAIMS, job.error().stage());
        assertEquals(2, verificationCalls.get());
    }

    private Extraction extraction() {
        if (isAttribution()) return new Extraction(List.of(new DraftClaim(SPEECH, "김민수가 예산 감소를 말했는가?",
            List.of("김민수 예산 감소 발언"), ClaimKind.ATTRIBUTION, "김민수")), List.of(), false);
        return new Extraction(List.of(new DraftClaim("새빛시 복지 예산이 " + (scenario.equals("refute") ? "30" : "12") + "% 감소한다.", "예산 감소율은 얼마인가?", List.of("새빛시 복지 예산 감소율"), ClaimKind.FACT, "")),
            scenario.equals("no-sources") ? List.of() : List.of(new DraftSource("새빛시 예산안", SourceType.DOCUMENT, CITATION, List.of(0), !scenario.equals("anonymous"))), false);
    }
    private boolean isAttribution() { return scenario.startsWith("attribution-"); }
    private Assessment assessment() {
        if (scenario.equals("anonymous")) {
            Criterion unknown = new Criterion(CriterionStatus.UNKNOWN, "정보원 신원을 외부에서 특정할 수 없습니다.", List.of());
            Criterion unnamed = new Criterion(CriterionStatus.NOT_MET, "신원이 명시되어 있지 않습니다.", List.of());
            return new Assessment(List.of(new SourceReview("source-1", "신원을 특정하지 않았습니다.", new Imvain(unknown, unknown, unknown, unknown, unknown, unnamed), List.of())));
        }
        Criterion met = new Criterion(CriterionStatus.MET, "공식 원문에서 확인했습니다.", scenario.equals("no-evidence") ? List.of() : List.of("evidence-1"));
        Criterion no = new Criterion(CriterionStatus.NOT_MET, "기사에서 독립적인 다른 정보원이 확인되지 않습니다.", List.of());
        return new Assessment(List.of(new SourceReview("source-1", "공식 자료를 확인했습니다.", new Imvain(no, no, met, met, met, met),
            scenario.equals("no-evidence") ? List.of() : List.of(new Quote("evidence-1", QUOTE)))));
    }
    private WebTransport.Response html(String body) { return new WebTransport.Response(200, "text/html; charset=utf-8", null, body.getBytes(StandardCharsets.UTF_8)); }
    private WebTransport.Response response(int code, Object body) { return new WebTransport.Response(code, "application/json", null, json.writeValueAsBytes(body)); }
    private HttpResponse<String> post(String body, String contentType) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/analyses"))
            .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertError(String body, String type, int status, String code) throws Exception {
        var response = post(body, type);
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("error").path("code").asString());
        assertFalse(response.body().contains("test-secret"));
    }
    private Snapshot submitAndWait() throws Exception {
        var response = post("{\"articleUrl\":\"" + URL + "\"}", "application/json");
        assertEquals(202, response.statusCode(), response.body());
        return await(json.readTree(response.body()));
    }
    private Snapshot await(JsonNode accepted) throws Exception {
        UUID id = UUID.fromString(accepted.path("analysisId").asString());
        for (int i = 0; i < 500; i++) {
            var statusResponse = get(id.toString());
            assertEquals(200, statusResponse.statusCode());
            Status status = Status.valueOf(json.readTree(statusResponse.body()).path("status").asString());
            if (Set.of(Status.COMPLETED, Status.PARTIAL_COMPLETED, Status.FAILED).contains(status)) {
                var resultResponse = get(id + "/result");
                assertEquals(status == Status.FAILED ? 409 : 200, resultResponse.statusCode(), resultResponse.body());
                if (status != Status.FAILED) assertEquals(status.toString(), json.readTree(resultResponse.body()).path("status").asString());
                return store.find(id).orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Background job did not terminate");
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/analyses/" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
