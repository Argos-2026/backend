package org.argos.argos_backend.analysis.controller;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.external.http.WebTransport;
import org.argos.argos_backend.external.llm.StructuredLlm;
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
    private static final String ARTICLE = "https://news.example.com/story";
    private static final String REPORT = "https://other.example.com/report";
    private static final String REPORT2 = "https://another.example.com/report";
    private static final String ARTICLE_QUOTE = "새빛시 예산안은 내년도 복지 예산이 12% 감소한다고 밝혔다.";
    private static final String REPORT_QUOTE = "새빛시 내년도 복지 예산은 전년 대비 12% 감소한다고 발표됐다.";
    private static final String CONTRARY_QUOTE = "새빛시 내년도 복지 예산 감소율은 20%라고 보도됐다.";
    private static final String SOURCE_QUOTE = "새빛시 기관은 내년도 복지 예산을 12% 줄인다고 발표했다.";
    @LocalServerPort int port;
    @Autowired AnalysisStore store;
    @Autowired JsonMapper json;
    @MockitoBean WebTransport transport;
    @MockitoBean StructuredLlm llm;
    private final HttpClient http = HttpClient.newHttpClient();
    private String scenario;
    private AtomicInteger extractionCalls;
    private AtomicInteger searchCalls;
    private AtomicInteger failedFetches;
    private List<URI> searchUris;

    @BeforeEach void setup() {
        scenario = "success";
        extractionCalls = new AtomicInteger();
        searchCalls = new AtomicInteger();
        failedFetches = new AtomicInteger();
        searchUris = Collections.synchronizedList(new ArrayList<>());
        when(transport.exchange(any(), anyString(), anyMap(), nullable(byte[].class), any(), anyInt()))
            .thenAnswer(invocation -> {
                URI uri = invocation.getArgument(0);
                if (uri.getHost().equals("news.example.com")) return html("""
                    <html><head><meta property="og:title" content="새빛시 복지 예산 발표">
                    <meta property="og:site_name" content="원본일보"></head><body><article>%s
                    새빛시가 내년도 복지 사업과 예산 편성 경위를 설명했다. 첫째 정보원과 둘째 정보원이 자료를 인용했다. 시는 주요 사업의 조정 내용과
                    집행 계획을 공개했으며 관련 기관은 추가 자료를 발표할 예정이다.</article></body></html>
                    """.formatted(ARTICLE_QUOTE));
                if (uri.getHost().equals("other.example.com")) {
                    if (uri.getPath().startsWith("/fail-")) {
                        failedFetches.incrementAndGet();
                        return response(404, Map.of());
                    }
                    if (uri.getPath().startsWith("/unique-")) {
                        String suffix = uri.getPath().substring("/unique-".length());
                        return html("<html><head><title>독립 보도 " + suffix
                            + "</title><meta property=\"og:site_name\" content=\"다른일보\"></head><body><main>"
                            + REPORT_QUOTE + (" 차별 내용 " + suffix).repeat(30) + "</main></body></html>");
                    }
                    return html("""
                    <html><head><title>타사 보도</title><meta property="og:site_name" content="%s"></head>
                    <body><main>%s 이 수치는 새빛시가 공개한 예산 자료를 근거로 보도했다.</main></body></html>
                    """.formatted(scenario.equals("same-publisher") ? "원본일보" : "다른일보", REPORT_QUOTE));
                }
                if (uri.getHost().equals("another.example.com")) return html("""
                    <html><head><title>상반 보도</title><meta property="og:site_name" content="또다른일보"></head>
                    <body><main>%s 새빛시 발표 내용을 확인했다고 전했다.</main></body></html>
                    """.formatted(CONTRARY_QUOTE));
                if (uri.getHost().equals("docs.example.com")) {
                    String suffix = uri.getPath().substring("/source-".length());
                    return html("<html><head><title>정보원 확인 자료 " + suffix
                        + "</title></head><body><main>" + SOURCE_QUOTE
                        + (" 관련 자료 " + suffix).repeat(30) + "</main></body></html>");
                }
                if (uri.getHost().contains("ntruss") || uri.getHost().contains("brave")) {
                    searchCalls.incrementAndGet();
                    searchUris.add(uri);
                    if (scenario.equals("all-search-failed") || (scenario.equals("partial-search") && uri.getHost().contains("ntruss")))
                        return response(503, Map.of());
                    List<?> hits = scenario.equals("source-four-candidates") && uri.getRawQuery().contains("imvain-")
                        ? java.util.stream.IntStream.rangeClosed(1, 4)
                            .mapToObj(i -> hit("https://docs.example.com/source-" + i)).toList()
                        : scenario.equals("no-reports")
                        || ((scenario.equals("retry-candidates") || scenario.equals("six-candidates"))
                            && uri.getRawQuery().contains("imvain-")) ? List.of()
                        : scenario.equals("six-candidates") ? uri.getHost().contains("ntruss")
                            ? java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> hit("https://other.example.com/unique-" + i)).toList()
                            : List.of(hit("https://other.example.com/unique-6"))
                        : scenario.equals("retry-candidates") ? uri.getHost().contains("ntruss")
                            ? java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> hit("https://other.example.com/fail-" + i)).toList()
                            : java.util.stream.IntStream.rangeClosed(6, 9).mapToObj(i -> hit("https://other.example.com/fail-" + i))
                                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                                    list -> { list.add(hit(REPORT)); return list; }))
                        : scenario.equals("mixed") ? List.of(hit(REPORT), hit(REPORT2))
                        : scenario.equals("contradicted") ? List.of(hit(REPORT2)) : List.of(hit(REPORT));
                    return response(200, uri.getHost().contains("ntruss") ? Map.of("items", hits)
                        : Map.of("web", Map.of("results", hits)));
                }
                throw new AssertionError("Unexpected external host " + uri.getHost());
            });
        when(llm.generate(anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            String input = invocation.getArgument(1);
            Object value = switch (prompt) {
                case "extract" -> {
                    extractionCalls.incrementAndGet();
                    yield extraction();
                }
                case "select" -> new ReportSelection(scenario.equals("six-candidates")
                    ? java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> new Quote("evidence-" + i, REPORT_QUOTE)).toList()
                    : scenario.equals("contradicted") ? List.of(new Quote("evidence-1", CONTRARY_QUOTE))
                    : scenario.equals("mixed")
                    ? List.of(new Quote("evidence-1", REPORT_QUOTE), new Quote("evidence-2", CONTRARY_QUOTE))
                    : List.of(new Quote("evidence-1", REPORT_QUOTE)));
                case "verify" -> new ReportReview(scenario.equals("unrelated")
                    ? List.of(new ReportDecision("evidence-1", DecisionStance.UNRELATED, "다른 사건입니다."))
                    : scenario.equals("contradicted")
                    ? List.of(new ReportDecision("evidence-1", DecisionStance.CONTRADICTS, "감소율이 다릅니다."))
                    : scenario.equals("six-candidates")
                    ? java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(i -> new ReportDecision("evidence-" + i, DecisionStance.MATCHES, "12%가 같습니다.")).toList()
                    : scenario.equals("mixed")
                    ? List.of(new ReportDecision("evidence-1", DecisionStance.MATCHES, "12%가 같습니다."),
                        new ReportDecision("evidence-2", DecisionStance.CONTRADICTS, "20%로 다릅니다."))
                    : List.of(new ReportDecision("evidence-1", DecisionStance.MATCHES, "12%가 같습니다.")));
                case "source-select" -> new SourceSelection(scenario.equals("source-four-candidates")
                    ? java.util.stream.IntStream.rangeClosed(2, 4)
                        .mapToObj(i -> new Quote("evidence-" + i, SOURCE_QUOTE)).toList() : List.of());
                case "assess" -> {
                    if (scenario.equals("source-failed") && json.readTree(input).path("source").path("name").asString().equals("첫째 정보원"))
                        throw new AnalysisFailure("LLM_FAILED", "평가 실패");
                    yield sourceReview(!json.readTree(input).path("source").path("identifiable").asBoolean());
                }
                case "summarize" -> new OverallSummary("두 분석 결과를 확인하세요.",
                    "다른 언론사의 보도 일치 여부입니다.", "정보원별 신뢰성 평가입니다.", List.of());
                default -> throw new AssertionError("Unexpected prompt " + prompt);
            };
            @SuppressWarnings("unchecked") Consumer<Object> validator = invocation.getArgument(3);
            validator.accept(value);
            return value;
        });
    }

    @Test void independentlyExtractedListsProduceSeparateResultsWithOneExtractionCall() throws Exception {
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(1, extractionCalls.get());
        assertEquals(ReportVerdict.MATCHED, job.result().crossReports().getFirst().verdict());
        assertEquals(1, job.result().crossReports().getFirst().matchingCount());
        assertEquals("다른일보", job.result().evidence().getFirst().publisher());
        assertEquals("다른 주장", job.result().sourceAssessments().getFirst().text());
        assertEquals(2, job.result().sourceAssessments().getFirst().sources().size());
        assertEquals(6, job.result().sourceAssessments().getFirst().sources().getFirst().imvain().size());
        assertEquals(8, searchCalls.get()); // 2 queries x 2 APIs, plus 2 queries x 2 sources.
        assertTrue(searchUris.stream().allMatch(uri -> uri.getRawQuery().contains(
            uri.getHost().contains("ntruss") ? "display=5" : "count=5")));
        JsonNode result = json.readTree(get(job.analysisId() + "/result").body());
        assertTrue(result.has("crossReports"));
        assertTrue(result.has("sourceAssessments"));
        assertFalse(result.has("claims"));
        assertFalse(result.has("articleSources"));
        int previousSearchCalls = searchCalls.get();
        var traceResponse = get(job.analysisId() + "/cross-report/trace");
        assertEquals(200, traceResponse.statusCode());
        JsonNode trace = json.readTree(traceResponse.body()).path("claims").get(0);
        assertEquals("report-1", trace.path("claimId").asString());
        assertEquals(4, trace.path("searches").size());
        assertEquals(4, trace.path("candidates").size());
        assertEquals("evidence-1", trace.path("selectedEvidenceIds").get(0).asString());
        assertEquals(previousSearchCalls, searchCalls.get());
        assertFalse(traceResponse.body().contains(REPORT_QUOTE));
    }

    @Test void mixedReportsCountMatchingAndContradictingArticles() throws Exception {
        scenario = "mixed";
        CrossReport report = submitAndWait().result().crossReports().getFirst();
        assertEquals(ReportVerdict.MIXED, report.verdict());
        assertEquals(1, report.matchingCount());
        assertEquals(1, report.contradictingCount());
        assertEquals(2, report.comparisons().size());
    }

    @Test void contradictingReportGetsContradictedVerdict() throws Exception {
        scenario = "contradicted";
        CrossReport report = submitAndWait().result().crossReports().getFirst();
        assertEquals(ReportVerdict.CONTRADICTED, report.verdict());
        assertEquals(0, report.matchingCount());
        assertEquals(1, report.contradictingCount());
    }

    @Test void failedCandidateFetchesContinueUntilTenthUniqueUrl() throws Exception {
        scenario = "retry-candidates";
        Snapshot job = submitAndWait();
        assertEquals(Status.PARTIAL_COMPLETED, job.status());
        assertEquals(ReportVerdict.MATCHED, job.result().crossReports().getFirst().verdict());
        assertEquals(9, failedFetches.get());
    }

    @Test void finalComparisonUsesAtMostFiveOfSixFetchedArticles() throws Exception {
        scenario = "six-candidates";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(5, job.result().crossReports().getFirst().comparisons().size());
        assertEquals(5, job.result().crossReports().getFirst().matchingCount());
        assertEquals(5, job.result().evidence().size());
    }

    @Test void sameClaimMayAppearInBothIndependentLists() throws Exception {
        scenario = "overlap";
        Snapshot job = submitAndWait();
        assertEquals(job.result().crossReports().getFirst().text(),
            job.result().sourceAssessments().getFirst().text());
        assertEquals(1, extractionCalls.get());
    }

    @Test void eachPurposeCanSelectThreeClaimsIndependently() throws Exception {
        scenario = "three-each";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(3, job.result().crossReports().size());
        assertEquals(3, job.result().sourceAssessments().size());
        assertEquals(1, extractionCalls.get());
    }

    @Test void sameSourceIsAssessedSeparatelyForEachClaim() throws Exception {
        scenario = "repeat-source";
        Snapshot job = submitAndWait();
        assertEquals(2, job.result().sourceAssessments().size());
        assertEquals("첫째 정보원", job.result().sourceAssessments().get(0).sources().getFirst().name());
        assertEquals("첫째 정보원", job.result().sourceAssessments().get(1).sources().getFirst().name());
        assertNotEquals(job.result().sourceAssessments().get(0).sources().getFirst().sourceId(),
            job.result().sourceAssessments().get(1).sources().getFirst().sourceId());
    }

    @Test void sourceUsesAtMostThreeOfFourExternalDocuments() throws Exception {
        scenario = "source-four-candidates";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        for (ReviewedSource source : job.result().sourceAssessments().getFirst().sources())
            assertEquals(3, source.imvain().get("verify").evidenceIds().size());
        assertEquals(4, job.result().evidence().size()); // One comparison article and three source documents.
    }

    @Test void unavailableReportsAreNotTreatedAsFalse() throws Exception {
        scenario = "no-reports";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertEquals(ReportVerdict.UNCONFIRMED, job.result().crossReports().getFirst().verdict());
        assertFalse(job.result().crossReports().getFirst().analysisFailed());
    }

    @Test void unrelatedCandidateDoesNotBecomeMatchingReport() throws Exception {
        scenario = "unrelated";
        Snapshot job = submitAndWait();
        assertEquals(ReportVerdict.UNCONFIRMED, job.result().crossReports().getFirst().verdict());
        assertEquals(0, job.result().crossReports().getFirst().matchingCount());
        assertTrue(job.result().evidence().isEmpty());
    }

    @Test void originalPublisherIsExcludedFromCrossReports() throws Exception {
        scenario = "same-publisher";
        Snapshot job = submitAndWait();
        assertEquals(ReportVerdict.UNCONFIRMED, job.result().crossReports().getFirst().verdict());
        assertEquals(0, job.result().crossReports().getFirst().matchingCount());
    }

    @Test void oneSearchProviderFailurePreservesOtherResults() throws Exception {
        scenario = "partial-search";
        Snapshot job = submitAndWait();
        assertEquals(Status.PARTIAL_COMPLETED, job.status());
        assertEquals(ReportVerdict.MATCHED, job.result().crossReports().getFirst().verdict());
        assertTrue(job.result().warnings().stream().anyMatch(w -> w.code().equals("SEARCH_FAILED")));
    }

    @Test void oneSourceFailureKeepsOtherSourceAssessment() throws Exception {
        scenario = "source-failed";
        Snapshot job = submitAndWait();
        assertEquals(Status.PARTIAL_COMPLETED, job.status());
        var sources = job.result().sourceAssessments().getFirst().sources();
        assertTrue(sources.get(0).assessmentFailed());
        assertFalse(sources.get(1).assessmentFailed());
        assertEquals(CriterionStatus.UNKNOWN, sources.get(0).imvain().get("verify").status());
    }

    @Test void anonymousSourceIsNotNamedAndUnknownWithoutExternalProof() throws Exception {
        scenario = "anonymous";
        var source = submitAndWait().result().sourceAssessments().getFirst().sources().getFirst();
        assertEquals(CriterionStatus.NOT_MET, source.imvain().get("named").status());
        assertEquals(CriterionStatus.UNKNOWN, source.imvain().get("authoritative").status());
    }

    @Test void bothListsCanBeEmpty() throws Exception {
        scenario = "empty";
        Snapshot job = submitAndWait();
        assertEquals(Status.COMPLETED, job.status());
        assertTrue(job.result().crossReports().isEmpty());
        assertTrue(job.result().sourceAssessments().isEmpty());
    }

    @Test void totalSearchFailureProducesFailedJob() throws Exception {
        scenario = "all-search-failed";
        Snapshot job = submitAndWait();
        assertEquals(Status.FAILED, job.status());
        assertEquals("ANALYSIS_FAILED", job.error().code());
        var response = get(job.analysisId() + "/cross-report/trace");
        assertEquals(200, response.statusCode());
        JsonNode trace = json.readTree(response.body());
        assertEquals("FAILED", trace.path("status").asString());
        JsonNode claim = trace.path("claims").get(0);
        assertEquals("새빛시 복지 예산 12% 감소", claim.path("exactQuery").asString());
        assertEquals("SEARCH_FAILED", claim.path("failure").path("code").asString());
        assertEquals(4, claim.path("searches").size());
        for (JsonNode request : claim.path("searches")) assertEquals("FAILED", request.path("status").asString());
        assertFalse(response.body().contains("test-openai"));
        assertFalse(response.body().contains("test-secret"));
        assertFalse(response.body().contains("test-brave"));
    }

    private Extraction extraction() {
        if (scenario.equals("empty")) return new Extraction(List.of(), List.of());
        DraftSource first = new DraftSource("첫째 정보원", "새빛시", SourceType.ORGANIZATION, ARTICLE_QUOTE,
            !scenario.equals("anonymous"), "imvain-첫째 정보원 소속", "imvain-첫째 정보원 복지 예산 12%");
        DraftSource second = new DraftSource("둘째 정보원", "", SourceType.PERSON, ARTICLE_QUOTE, true,
            "imvain-둘째 정보원 신원", "imvain-둘째 정보원 복지 예산 12%");
        String reportText = "새빛시 복지 예산이 12% 감소한다.";
        String sourceText = scenario.equals("overlap") ? reportText : "다른 주장";
        List<DraftSourceClaim> sourceClaims = new ArrayList<>();
        sourceClaims.add(new DraftSourceClaim(sourceText, List.of(first, second)));
        if (scenario.equals("repeat-source"))
            sourceClaims.add(new DraftSourceClaim("두 번째 정보원 포함 주장", List.of(first)));
        List<DraftCrossClaim> reports = new ArrayList<>();
        reports.add(new DraftCrossClaim(reportText, "새빛시 복지 예산 12% 감소", "새빛시 내년도 복지 예산 변화"));
        if (scenario.equals("three-each")) {
            reports.add(new DraftCrossClaim("새빛시 복지 사업을 조정한다.", "새빛시 복지 사업 조정", "새빛시 사업 개편"));
            reports.add(new DraftCrossClaim("새빛시가 예산 자료를 공개했다.", "새빛시 예산 자료 공개", "새빛시 예산 발표"));
            sourceClaims.add(new DraftSourceClaim("두 번째 정보원 포함 주장", List.of(first)));
            sourceClaims.add(new DraftSourceClaim("세 번째 정보원 포함 주장", List.of(second)));
        }
        return new Extraction(reports, sourceClaims);
    }
    private SourceReview sourceReview(boolean anonymous) {
        Criterion unknown = new Criterion(CriterionStatus.UNKNOWN, "외부에서 확인하지 못했습니다.", List.of());
        Criterion named = new Criterion(anonymous ? CriterionStatus.NOT_MET : CriterionStatus.MET,
            anonymous ? "익명입니다." : "기사에 이름이 있습니다.", List.of());
        Criterion multiple = new Criterion(CriterionStatus.MET, "기사 내부에 다른 정보원이 있습니다.", List.of());
        if (scenario.equals("source-four-candidates")) {
            List<String> ids = List.of("evidence-2", "evidence-3", "evidence-4");
            Criterion verified = new Criterion(CriterionStatus.MET, "외부 원문에서 인용을 확인했습니다.", ids);
            return new SourceReview("외부 자료를 확인했습니다.",
                new Imvain(unknown, multiple, verified, unknown, unknown, named),
                ids.stream().map(id -> new Quote(id, SOURCE_QUOTE)).toList());
        }
        return new SourceReview("기사와 외부 자료를 비교했습니다.",
            new Imvain(unknown, multiple, unknown, unknown, unknown, named), List.of());
    }
    private Map<String, String> hit(String url) {
        return Map.of("url", url, "originallink", url, "title", "예산 보도");
    }
    private WebTransport.Response html(String body) {
        return new WebTransport.Response(200, "text/html; charset=utf-8", null, body.getBytes(StandardCharsets.UTF_8));
    }
    private WebTransport.Response response(int code, Object body) {
        return new WebTransport.Response(code, "application/json", null, json.writeValueAsBytes(body));
    }
    private Snapshot submitAndWait() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/analyses"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                "{\"articleUrl\":\"" + ARTICLE + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        UUID id = UUID.fromString(json.readTree(response.body()).path("analysisId").asString());
        for (int i = 0; i < 600; i++) {
            Snapshot snapshot = store.find(id).orElseThrow();
            if (Set.of(Status.COMPLETED, Status.PARTIAL_COMPLETED, Status.FAILED).contains(snapshot.status())) return snapshot;
            Thread.sleep(10);
        }
        throw new AssertionError("Background job did not finish");
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/analyses/" + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
