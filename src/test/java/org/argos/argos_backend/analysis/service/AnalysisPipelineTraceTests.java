package org.argos.argos_backend.analysis.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.analysis.domain.AnalysisJob;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.*;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.llm.StructuredLlm;
import org.argos.argos_backend.external.search.SearchClient;
import org.argos.argos_backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.external.llm.LlmModels.*;
import static org.argos.argos_backend.external.search.SearchClient.Provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisPipelineTraceTests {
    private static final String ARTICLE = "https://news.example.com/original";
    private static final String EXACT = "\"새빛시\" 복지 예산 12% 2027";
    private static final String EXPANDED = "새빛시 내년도 복지 사업 변경";
    private static final String QUOTE = "확인한 예산 문장";
    private static final UUID ID = UUID.fromString("a0f02db8-c8af-48db-b9fd-5d6f62303a11");

    @Test void preservesAllTwentyHitsAndOnlyFetchesFirstTenWithoutInventingLaterStages() {
        try (Fixture f = new Fixture()) {
            f.twenty = true;
            Result result = f.run();
            ClaimTrace trace = f.claim();
            assertEquals(EXACT, trace.exactQuery());
            assertEquals(EXPANDED, trace.expandedQuery());
            assertEquals(4, trace.searches().size());
            for (SearchTrace request : trace.searches()) {
                assertEquals(RequestStatus.SUCCEEDED, request.status());
                assertEquals(5, request.resultCount());
                assertEquals(request.queryType() == QueryType.EXACT ? EXACT : EXPANDED, request.query());
                verify(f.search).search(eq(request.provider()), eq(request.query()), eq(5), same(f.context));
            }
            assertEquals(20, trace.candidates().size());
            assertEquals(10, trace.candidates().stream().filter(c -> has(c, "FETCH_SUCCEEDED")).count());
            assertEquals(10, trace.candidates().stream().filter(c -> has(c, "COLLECTION_LIMIT_REACHED")).count());
            trace.candidates().stream().filter(c -> has(c, "COLLECTION_LIMIT_REACHED")).forEach(c -> {
                assertEquals(1, c.events().size());
                assertNull(c.evidenceId()); assertNull(c.llmSelected()); assertNull(c.comparison());
            });
            assertEquals(9, trace.candidates().stream().filter(c -> Boolean.FALSE.equals(c.llmSelected())).count());
            assertEquals(List.of("evidence-1"), trace.selectedEvidenceIds());
            assertEquals(ReportVerdict.MATCHED, result.crossReports().getFirst().verdict());
            verify(f.fetcher, times(10)).fetch(anyString(), eq(false), same(f.context));
            String serialized = f.json.writeValueAsString(trace);
            assertFalse(serialized.contains("외부 본문 비공개 표식"));
            assertFalse(serialized.contains("selectionReason"));
        }
    }

    @Test void recordsJavaFiltersAndLeavesUnperformedSelectionNull() {
        try (Fixture f = new Fixture()) {
            f.hits = List.of(hit("http://localhost/private"), hit(ARTICLE), hit(url(1)), hit(url(1)),
                hit(url(2)), hit(url(3)), hit(url(4)), hit(url(5)), hit(url(6)));
            f.docs.put(url(1), doc(url(1), "타사", f.article.body()));
            f.docs.put(url(2), doc("https://news.example.com/other", "타사", "동일 호스트 문서"));
            f.docs.put(url(3), doc(url(3), "원본일보", "동일 언론사의 별개 문서"));
            f.docs.put(url(5), doc(url(5), "타사", f.document(4).body()));
            f.redirectRejected.add(url(6));
            f.run();
            assertTrue(has(f.candidate("http://localhost/private"), "URL_REJECTED"));
            assertTrue(has(f.candidate(ARTICLE), "ORIGINAL_ARTICLE_URL"));
            assertTrue(has(f.candidate(url(1)), "SAME_CONTENT_AS_ORIGINAL"));
            assertEquals(1, f.claim().candidates().stream().filter(c -> has(c, "DUPLICATE_URL")).count());
            assertTrue(has(f.candidate(url(2)), "SAME_HOST_AS_ORIGINAL"));
            assertTrue(has(f.candidate(url(3)), "SAME_PUBLISHER_AS_ORIGINAL"));
            assertTrue(has(f.candidate(url(5)), "EVIDENCE_REUSED"));
            assertEquals(f.candidate(url(4)).evidenceId(), f.candidate(url(5)).evidenceId());
            for (String accepted : List.of(url(4), url(5))) {
                CandidateTrace candidate = f.candidate(accepted);
                assertEquals(Boolean.TRUE, candidate.llmSelected());
                assertTrue(has(candidate, "LLM_SELECTED"));
                assertTrue(has(candidate, "COMPARED"));
                assertEquals("MATCHES", candidate.comparison().stance());
                assertTrue(f.claim().selectedEvidenceIds().contains(candidate.evidenceId()));
            }
            assertTrue(has(f.candidate(url(6)), "FETCH_URL_REJECTED"));
            for (String excluded : List.of("http://localhost/private", ARTICLE, url(1), url(2), url(3), url(6))) {
                assertNull(f.candidate(excluded).llmSelected());
                assertNull(f.candidate(excluded).comparison());
            }
        }
    }

    @Test void recordsFetchFailureUsingExistingSafeExceptionAndContinues() {
        try (Fixture f = new Fixture()) {
            f.hits = List.of(hit(url(1)), hit(url(2)));
            f.fetchFailed.add(url(1));
            Result result = f.run();
            assertEquals(Status.PARTIAL_COMPLETED, result.status());
            CandidateTrace failed = f.candidate(url(1));
            assertTrue(has(failed, "ARTICLE_FETCH_FAILED"));
            assertFalse(has(failed, "FETCH_SUCCEEDED"));
            assertNull(failed.llmSelected());
            assertEquals("기사 원문을 가져오지 못했습니다.", failed.events().getLast().message());
            assertEquals(ReportVerdict.MATCHED, result.crossReports().getFirst().verdict());
            CandidateTrace selected = f.candidate(url(2));
            assertEquals(Boolean.TRUE, selected.llmSelected());
            assertTrue(has(selected, "LLM_SELECTED"));
            assertTrue(has(selected, "COMPARED"));
        }
    }

    @Test void excludedCandidateBetweenSelectedCandidatesPreservesTraceAndMixedVerdict() {
        try (Fixture f = new Fixture()) {
            f.hits = List.of(hit(url(1)), hit(url(2)), hit(url(3)));
            f.fetchFailed.add(url(2));
            f.selected = List.of(new Quote("evidence-1", QUOTE), new Quote("evidence-2", QUOTE));
            f.decisions = List.of(new ReportDecision("evidence-1", DecisionStance.MATCHES, "일치 이유"),
                new ReportDecision("evidence-2", DecisionStance.CONTRADICTS, "상반 이유"));
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(ReportVerdict.MIXED, report.verdict());
            assertEquals(1, report.matchingCount()); assertEquals(1, report.contradictingCount());
            assertEquals(List.of("evidence-1", "evidence-2"), f.claim().selectedEvidenceIds());
            for (String url : List.of(url(1), url(3))) {
                CandidateTrace candidate = f.candidate(url);
                assertEquals(Boolean.TRUE, candidate.llmSelected());
                assertTrue(has(candidate, "LLM_SELECTED"));
                assertTrue(has(candidate, "COMPARED"));
                assertEquals(candidate.evidenceId(), candidate.comparison().evidenceId());
            }
            assertNull(f.candidate(url(2)).llmSelected());
            assertNull(f.candidate(url(2)).comparison());
        }
    }

    @Test void totalFetchFailurePreservesCandidatesAndFailureStage() {
        try (Fixture f = new Fixture()) {
            f.fetchFailed.add(url(1));
            assertThrows(AnalysisFailure.class, f::run);
            assertEquals("SEARCH_FAILED", f.claim().failure().code());
            assertEquals(Stage.RETRIEVING_EVIDENCE, f.claim().failure().stage());
            assertEquals("검색 후보 원문을 모두 수집하지 못했습니다.", f.claim().failure().message());
            assertNull(f.candidate(url(1)).llmSelected());
        }
    }

    @Test void unexpectedFailureRetainsTypeButNeverArbitraryExceptionContent() {
        try (Fixture f = new Fixture()) {
            when(f.fetcher.fetch(eq(url(1)), eq(false), same(f.context)))
                .thenThrow(new IllegalStateException("Authorization: Bearer private-secret; entire remote body"));
            assertThrows(IllegalStateException.class, f::run);
            assertEquals("INTERNAL_SERVER_ERROR", f.claim().failure().code());
            assertTrue(f.claim().failure().message().contains("IllegalStateException"));
            assertTrue(has(f.candidate(url(1)), "CANDIDATE_PROCESSING_FAILED"));
            String trace = f.json.writeValueAsString(f.context.traceSnapshot());
            assertFalse(trace.contains("private-secret"));
            assertFalse(trace.contains("entire remote body"));
        }
    }

    @Test void providerFailurePreservesSafeDetailsAndOtherSearchResults() {
        try (Fixture f = new Fixture()) {
            f.searchFailed.add(BRAVE);
            Result result = f.run();
            assertEquals(ReportVerdict.MATCHED, result.crossReports().getFirst().verdict());
            assertEquals(2, f.claim().searches().stream().filter(s -> s.status() == RequestStatus.FAILED).count());
            f.claim().searches().stream().filter(s -> s.status() == RequestStatus.FAILED).forEach(s -> {
                assertNull(s.resultCount());
                assertEquals("외부 서비스 API 인증에 실패했습니다. (HTTP 401)", s.failure().message());
            });
        }
    }

    @Test void totalSearchFailureStillHasFourRequestsAndNoInventedCandidates() {
        try (Fixture f = new Fixture()) {
            f.searchFailed.addAll(Set.of(NAVER, BRAVE));
            assertThrows(AnalysisFailure.class, f::run);
            assertEquals(4, f.claim().searches().size());
            assertTrue(f.claim().searches().stream().allMatch(s -> s.status() == RequestStatus.FAILED));
            assertTrue(f.claim().candidates().isEmpty());
            assertEquals("SEARCH_FAILED", f.claim().failure().code());
        }
    }

    @Test void emptySelectionRecordsRejectionWithoutFabricatingReasonsOrComparison() {
        try (Fixture f = new Fixture()) {
            f.selected = List.of();
            Result result = f.run();
            CandidateTrace candidate = f.candidate(url(1));
            assertEquals(Boolean.FALSE, candidate.llmSelected());
            assertTrue(has(candidate, "LLM_NOT_SELECTED"));
            assertNull(candidate.events().getLast().message());
            assertNull(candidate.comparison());
            assertEquals(ReportVerdict.UNCONFIRMED, result.crossReports().getFirst().verdict());
            assertFalse(result.crossReports().getFirst().analysisFailed());
            verify(f.llm, never()).generate(eq("verify"), anyString(), any(), any(), any());
        }
    }

    @ParameterizedTest
    @CsvSource({"select,SELECTING_EVIDENCE", "verify,VERIFYING_CLAIMS"})
    void llmFailureRecordsActualStageOnly(String prompt, Stage stage) {
        try (Fixture f = new Fixture()) {
            f.llmFailed = prompt;
            assertThrows(AnalysisFailure.class, f::run);
            assertEquals(stage, f.claim().failure().stage());
            assertEquals("LLM_FAILED", f.claim().failure().code());
            assertEquals(prompt.equals("select") ? null : Boolean.TRUE, f.candidate(url(1)).llmSelected());
            assertNull(f.candidate(url(1)).comparison());
        }
    }

    @ParameterizedTest
    @CsvSource({"MATCHES,MATCHED,1,0", "CONTRADICTS,CONTRADICTED,0,1", "UNRELATED,UNCONFIRMED,0,0"})
    void tracePreservesDecisionWithoutChangingVerdictOrAggregation(DecisionStance stance, ReportVerdict verdict,
                                                                  int matching, int contradicting) {
        try (Fixture f = new Fixture()) {
            f.decisions = List.of(new ReportDecision("evidence-1", stance, "실제 비교 이유"));
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(verdict, report.verdict());
            assertEquals(matching, report.matchingCount()); assertEquals(contradicting, report.contradictingCount());
            assertEquals(matching + contradicting, report.comparisons().size());
            assertEquals(new ComparisonTrace("evidence-1", stance.name(), "실제 비교 이유"), f.candidate(url(1)).comparison());
            assertEquals(verdict, f.claim().verdict());
            assertEquals(report.reason(), f.claim().reason());
        }
    }

    @Test void traceListenerFailureCannotChangeSuccessfulResultOrWarnings() {
        AtomicInteger attempted = new AtomicInteger();
        try (Fixture normal = new Fixture(); Fixture failing = new Fixture(ignored -> {
            attempted.incrementAndGet(); throw new IllegalStateException("trace storage failed");
        })) {
            assertEquals(normal.run(), failing.run());
            assertTrue(attempted.get() > 10);
            assertEquals(ReportVerdict.MATCHED, failing.claim().verdict());
        }
    }

    @Test void runningSearchesAreVisibleAndJobSnapshotsRemainImmutableAfterCompletion() throws Exception {
        Instant now = Instant.now();
        AnalysisJob job = new AnalysisJob(ID, ARTICLE, now, now.plusSeconds(86400));
        assertTrue(job.start());
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(job::trace); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            when(f.search.search(any(), anyString(), eq(5), same(f.context))).thenAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return List.of();
            });
            Future<Result> result = executor.submit(f::run);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Snapshot running = job.snapshot();
                assertEquals(4, running.traceClaims().getFirst().searches().size());
                assertTrue(running.traceClaims().getFirst().searches().stream().allMatch(s -> s.status() == RequestStatus.STARTED));
                assertTrue(running.traceClaims().getFirst().candidates().isEmpty());
                assertThrows(UnsupportedOperationException.class, () -> running.traceClaims().clear());
                release.countDown();
                assertTrue(job.complete(result.get(3, TimeUnit.SECONDS)));
                assertTrue(running.traceClaims().getFirst().searches().stream().allMatch(s -> s.status() == RequestStatus.STARTED));
                Snapshot completed = job.snapshot();
                assertTrue(completed.traceClaims().getFirst().searches().stream().allMatch(s -> s.status() == RequestStatus.SUCCEEDED));
                f.context.trace(t -> t.claim("late", "늦은 변경", "q", "q2"));
                assertEquals(completed, job.snapshot());
            } finally { release.countDown(); }
        }
    }

    private static boolean has(CandidateTrace candidate, String code) { return candidate.events().stream().anyMatch(e -> e.code().equals(code)); }
    private static String url(int n) { return "https://other.example.com/" + n; }
    private static SearchClient.Hit hit(String url) { return new SearchClient.Hit(url, "검색 제목"); }
    private static DocumentFetcher.Document doc(String url, String publisher, String body) {
        return new DocumentFetcher.Document(url, "기사 제목", publisher, null, null, body, false);
    }

    private static class Fixture implements AutoCloseable {
        final JsonMapper json = JsonMapper.builder().build();
        final DocumentFetcher fetcher = mock(DocumentFetcher.class);
        final SearchClient search = mock(SearchClient.class);
        final StructuredLlm llm = mock(StructuredLlm.class);
        final AnalysisContext context;
        final AnalysisPipeline pipeline;
        final DocumentFetcher.Document article = doc(ARTICLE, "원본일보", "원본 기사의 고유한 본문");
        final Map<String, DocumentFetcher.Document> docs = new HashMap<>();
        final Set<String> fetchFailed = new HashSet<>(), redirectRejected = new HashSet<>();
        final Set<SearchClient.Provider> searchFailed = new HashSet<>();
        boolean twenty;
        String llmFailed;
        List<SearchClient.Hit> hits = List.of(hit(url(1)));
        List<Quote> selected = List.of(new Quote("evidence-1", QUOTE));
        List<ReportDecision> decisions = List.of(new ReportDecision("evidence-1", DecisionStance.MATCHES, "같은 보도"));
        Fixture() { this(ignored -> {}); }
        Fixture(Consumer<List<ClaimTrace>> listener) {
            context = new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(600), ignored -> {}, listener);
            when(fetcher.fetch(anyString(), eq(true), same(context))).thenReturn(article);
            when(fetcher.fetch(anyString(), eq(false), same(context))).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                if (fetchFailed.contains(url)) throw new AnalysisFailure("ARTICLE_FETCH_FAILED", "기사 원문을 가져오지 못했습니다.");
                if (redirectRejected.contains(url)) throw new ApiException(422, "UNSUPPORTED_URL", "허용되지 않는 뉴스 URL입니다.");
                return docs.getOrDefault(url, doc(url, "타사", QUOTE + " 외부 본문 비공개 표식 " + url));
            });
            when(search.search(any(), anyString(), eq(5), same(context))).thenAnswer(invocation -> {
                SearchClient.Provider provider = invocation.getArgument(0);
                String query = invocation.getArgument(1);
                if (searchFailed.contains(provider)) throw new AnalysisFailure("SEARCH_FAILED", "외부 서비스 API 인증에 실패했습니다. (HTTP 401)");
                if (twenty) {
                    int offset = (query.equals(EXPANDED) ? 10 : 0) + (provider == BRAVE ? 5 : 0);
                    return IntStream.rangeClosed(offset + 1, offset + 5).mapToObj(n -> hit(url(n))).toList();
                }
                return provider == NAVER && query.equals(EXACT) ? hits : List.of();
            });
            when(llm.generate(anyString(), anyString(), any(), any(), same(context))).thenAnswer(invocation -> {
                String prompt = invocation.getArgument(0);
                if (prompt.equals(llmFailed)) throw new AnalysisFailure("LLM_FAILED", "분석 응답 형식 또는 근거 참조를 검증하지 못했습니다.");
                Object value = switch (prompt) {
                    case "extract" -> new Extraction(List.of(new DraftCrossClaim("복지 예산 조정", EXACT, EXPANDED)), List.of());
                    case "select" -> new ReportSelection(selected);
                    case "verify" -> new ReportReview(decisions);
                    case "summarize" -> new OverallSummary("요약", "교차보도 요약", "정보원 없음", List.of());
                    default -> throw new AssertionError(prompt);
                };
                @SuppressWarnings("unchecked") Consumer<Object> validate = invocation.getArgument(3);
                validate.accept(value);
                return value;
            });
            pipeline = new AnalysisPipeline(fetcher, search, llm, new SafeUrlPolicy(), json);
        }
        DocumentFetcher.Document document(int n) { return doc(url(n), "타사", QUOTE + " 외부 본문 비공개 표식 " + url(n)); }
        Result run() { return pipeline.run(ID, ARTICLE, context); }
        ClaimTrace claim() { return context.traceSnapshot().getFirst(); }
        CandidateTrace candidate(String url) { return claim().candidates().stream().filter(c -> c.url().equals(url)).findFirst().orElseThrow(); }
        @Override public void close() { pipeline.close(); }
    }
}
