package org.argos.argos_backend.analysis.service;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.argos.argos_backend.analysis.domain.*;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.*;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.external.document.DocumentFetcher.Document;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.llm.StructuredLlm;
import org.argos.argos_backend.external.search.SearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;
import static org.argos.argos_backend.external.llm.LlmModels.*;
import static org.argos.argos_backend.external.search.SearchClient.Provider.*;
import static org.argos.argos_backend.support.AnalysisFixtures.confirmedConflict;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportPerspectivePipelineTests {
    private static final String ORIGINAL = "새빛시는 2027년 복지 예산을 전년 대비 12% 줄인다고 발표했다.";
    private static final String SUPPORT = "새빛시의 2027년 복지 예산 감소율은 전년 대비 12%다.";
    private static final String CONFLICT = "새빛시의 2027년 복지 예산 감소율은 전년 대비 20%다.";
    private static final IssueFrame ISSUE = new IssueFrame("발표", "새빛시", "복지 예산", "2027년", "복지 예산", ORIGINAL);

    @Test void supportsOriginalWithoutInventingContradictionsAndBuildsGroundedNeutralQueries() {
        try (Fixture f = new Fixture()) {
            f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT);
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(ReportVerdict.MATCHED, report.verdict());
            assertEquals(1, report.matchingCount()); assertEquals(0, report.contradictingCount());
            ClaimTrace trace = f.trace();
            assertEquals(ISSUE, trace.issue());
            assertTrue(trace.neutralQuery().contains("12%")); // Preserve the claim's value as an event anchor.
            assertFalse(trace.challengeQuery().contains("20%"));
            assertEquals(6, trace.searches().size());
            assertEquals(Set.of(QueryType.EXACT, QueryType.EXPANDED, QueryType.NEUTRAL, QueryType.CHALLENGE),
                new HashSet<>(trace.searches().stream().map(SearchTrace::queryType).toList()));
            CandidateTrace candidate = trace.candidates().getFirst();
            assertEquals(Perspective.SUPPORT_CANDIDATE, candidate.claimAnalysis().perspective());
            assertEquals(List.of(SUPPORT), candidate.usedQuotes());
            assertEquals(Instant.parse("2026-11-01T00:00:00Z"), candidate.document().publishedAt());
            assertNull(candidate.conflictResolution());
            verify(f.llm, times(1)).generate(eq("perspectives"), anyString(), any(), any(), same(f.context));
            verify(f.llm, never()).generate(eq("select"), anyString(), any(), any(), any());
        }
    }

    @Test void findsGenuineConflictAlongsideSupportingReportsAndPreservesNearIdenticalBodies() {
        try (Fixture f = new Fixture()) {
            Spec support = f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT);
            Spec conflict = f.add(Perspective.CONFLICT_CANDIDATE, CONFLICT);
            // A one-number correction must survive the former 0.85 similarity deduplication.
            f.bodies.put(support.url, SUPPORT + " 공통 배경 설명입니다.".repeat(100));
            f.bodies.put(conflict.url, CONFLICT + " 공통 배경 설명입니다.".repeat(100));
            assertTrue(AnalysisPipeline.sameContent(f.bodies.get(support.url), f.bodies.get(conflict.url)));
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(ReportVerdict.MIXED, report.verdict());
            assertEquals(1, report.matchingCount()); assertEquals(1, report.contradictingCount());
            CandidateTrace candidate = f.candidate(conflict.url);
            assertTrue(candidate.conflictResolution().accepted());
            assertEquals(CONFLICT, candidate.conflictResolution().check().externalQuote());
            assertTrue(candidate.usedQuotes().contains(CONFLICT));
        }
    }

    @Test void nearIdenticalOriginalWithCorrectedNumberIsNotDiscardedAsOriginalReprint() {
        try (Fixture f = new Fixture()) {
            Spec conflict = f.add(Perspective.CONFLICT_CANDIDATE, ORIGINAL.replace("12%", "20%"));
            String background = java.util.stream.IntStream.range(0, 100).mapToObj(i -> " 공통 취재 배경 " + i + "번 설명입니다.").collect(java.util.stream.Collectors.joining());
            f.originalBody = ORIGINAL + background;
            f.bodies.put(conflict.url, conflict.quote + background);
            assertTrue(AnalysisPipeline.sameContent(f.originalBody, f.bodies.get(conflict.url)));
            assertEquals(ReportVerdict.CONTRADICTED, f.run().crossReports().getFirst().verdict());
            assertTrue(f.candidate(conflict.url).events().stream().anyMatch(e -> e.code().equals("SIMILAR_CONTENT_RETAINED")));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Perspective.class, names = {"DIFFERENT_PERSPECTIVE", "IRRELEVANT", "INSUFFICIENT"})
    void opinionsOtherEventsAndMissingBodyEvidenceNeverBecomeContradictions(Perspective perspective) {
        try (Fixture f = new Fixture()) {
            String text = switch (perspective) {
                case DIFFERENT_PERSPECTIVE -> "시민단체는 예산 조정을 긍정적으로 평가하지만 다른 단체는 우려했다.";
                case IRRELEVANT -> "다른시는 2027년 복지 예산을 20% 줄인다고 발표했다.";
                default -> "반박이라는 제목과 달리 기사 본문에는 비교 가능한 수치가 없다.";
            };
            Spec spec = f.add(perspective, text);
            assertEquals(ReportVerdict.UNCONFIRMED, f.run().crossReports().getFirst().verdict());
            assertEquals(perspective, f.candidate(spec.url).claimAnalysis().perspective());
            assertFalse(f.candidate(spec.url).verificationSelection().selected());
            assertNull(f.candidate(spec.url).llmSelected());
            verify(f.llm, never()).generate(eq("verify"), anyString(), any(), any(), any());
        }
    }

    @Test void compatibleOpinionDisagreementBetweenExternalArticlesDoesNotRefuteOriginal() {
        try (Fixture f = new Fixture()) {
            f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT);
            f.add(Perspective.DIFFERENT_PERSPECTIVE, "복지 예산 조정에 대해 긍정적인 전망을 제시했다.");
            f.add(Perspective.DIFFERENT_PERSPECTIVE, "복지 예산 조정에 대해 부정적인 전망을 제시했다.");
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(ReportVerdict.MATCHED, report.verdict());
            assertEquals(0, report.contradictingCount());
        }
    }

    @Test void changedPeriodIsRejectedEvenWhenModelCallsItAContradiction() {
        try (Fixture f = new Fixture()) {
            Spec spec = f.add(Perspective.CONFLICT_CANDIDATE, "새빛시의 2028년 복지 예산 감소율은 전년 대비 20%다.");
            ConflictCheck c = spec.check;
            spec.check = new ConflictCheck(c.sameEvent(), c.sameSubject(), c.sameTarget(), ScopeMatch.DIFFERENT,
                c.sameMetric(), c.sameUnit(), c.sameDenominator(), c.sameAggregation(), c.sameConditions(), true,
                ORIGINAL, spec.quote, "통계 대상 기간은 2028년으로 다릅니다.");
            assertEquals(ReportVerdict.UNCONFIRMED, f.run().crossReports().getFirst().verdict());
            assertFalse(f.candidate(spec.url).conflictResolution().accepted());
            assertEquals("CONTRADICTS", f.candidate(spec.url).comparison().stance());
            assertTrue(f.candidate(spec.url).usedQuotes().isEmpty());
        }
    }

    @Test void copiesOfSamePressReleaseAreNotCountedAsIndependentEvidence() {
        try (Fixture f = new Fixture()) {
            String attribution = "새빛시가 공개한 2027년 복지 예산 확정 보도자료를 인용했다.";
            Spec first = f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT);
            Spec second = f.add(Perspective.SUPPORT_CANDIDATE, "새빛시는 복지 예산을 2027년에 12% 감액한다.");
            second.duplicate = first.url; second.originQuote = attribution;
            f.bodies.put(first.url, SUPPORT + attribution + "첫 언론사의 추가 설명.");
            f.bodies.put(second.url, second.quote + attribution + "둘째 언론사의 해설.");
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(1, report.matchingCount());
            assertEquals("evidence-1", f.candidate(second.url).claimAnalysis().duplicateOfEvidenceId());
            assertFalse(f.candidate(second.url).verificationSelection().selected());
        }
    }

    @Test void conflictCandidateHasOpportunityWhenMoreThanFiveSupportingArticlesExist() {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 5; i++) f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT + " 기사 " + i);
            Spec conflict = f.add(Perspective.CONFLICT_CANDIDATE, CONFLICT);
            CrossReport report = f.run().crossReports().getFirst();
            assertEquals(5, report.comparisons().size());
            assertEquals(1, report.contradictingCount());
            assertTrue(f.candidate(conflict.url).verificationSelection().selected());
        }
    }

    @Test void additionalSearchRoutesReachCollectionWithoutIncreasingTenDocumentBudget() {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 30; i++) f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT + " 독립 자료 " + i);
            f.fullRoutes = true;
            f.run();
            assertEquals(30, f.trace().candidates().size());
            assertEquals(10, f.fetched.size());
            assertTrue(f.trace().candidates().stream().filter(c -> c.requestId().equals("search-6"))
                .anyMatch(c -> c.claimAnalysis() != null));
            assertEquals(20, f.trace().candidates().stream().filter(c ->
                c.events().stream().anyMatch(e -> e.code().equals("COLLECTION_LIMIT_REACHED"))).count());
        }
    }

    @Test void identicalProviderQueriesAreNotCalledTwice() {
        try (Fixture f = new Fixture()) {
            f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT); f.duplicateQueries = true;
            f.run();
            assertEquals(4, f.trace().searches().size());
            verify(f.search, times(1)).search(eq(NAVER), eq("원문 주장 12%"), eq(5), same(f.context));
            verify(f.search, times(1)).search(eq(BRAVE), eq("원문 주장 12%"), eq(5), same(f.context));
        }
    }

    @Test void fabricatedExtractionOrOmittedCandidateFallsBackWithoutInventingEvidence() {
        for (boolean omission : List.of(false, true)) try (Fixture f = new Fixture()) {
            f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT);
            f.badQuote = !omission; f.omitCandidate = omission;
            Result result = f.run();
            assertEquals(Status.PARTIAL_COMPLETED, result.status());
            assertEquals(ReportVerdict.MATCHED, result.crossReports().getFirst().verdict());
            assertEquals("PERSPECTIVE_ANALYSIS_FAILED", f.trace().enrichmentIssues().getFirst().code());
            assertNull(f.trace().candidates().getFirst().claimAnalysis());
            verify(f.llm, times(1)).generate(eq("select"), anyString(), any(), any(), same(f.context));
        }
    }

    @Test void optionalNodeTimeoutRecoversThroughLegacySelectionWhileJobBudgetRemains() {
        try (Fixture f = new Fixture()) {
            f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT); f.perspectiveFailure = true;
            assertEquals(ReportVerdict.MATCHED, f.run().crossReports().getFirst().verdict());
            assertFalse(f.trace().enrichmentIssues().isEmpty());
        }
    }

    @Test void invalidIssueUsesLegacySearchWithoutChangingImvainOrFailingWholeRequest() {
        try (Fixture f = new Fixture()) {
            f.issue = new IssueFrame("발표", "만들어낸 기관", "복지 예산", "2027년", "복지 예산", ORIGINAL);
            f.add(Perspective.SUPPORT_CANDIDATE, SUPPORT);
            Result result = f.run();
            assertEquals(ReportVerdict.MATCHED, result.crossReports().getFirst().verdict());
            assertEquals(4, f.trace().searches().size());
            assertEquals("ISSUE_FRAME_INVALID", f.trace().enrichmentIssues().getFirst().code());
        }
    }

    @Test void emptySearchAndFailedFetchDoNotUseHeadlinesToCreateContradictions() {
        try (Fixture empty = new Fixture()) {
            assertEquals(ReportVerdict.UNCONFIRMED, empty.run().crossReports().getFirst().verdict());
            assertTrue(empty.trace().candidates().isEmpty());
        }
        try (Fixture failed = new Fixture()) {
            failed.add(Perspective.CONFLICT_CANDIDATE, CONFLICT); failed.fetchFailure = true;
            assertThrows(AnalysisFailure.class, failed::run);
            assertTrue(failed.trace().candidates().stream().allMatch(c -> c.claimAnalysis() == null));
            verify(failed.llm, never()).generate(eq("verify"), anyString(), any(), any(), any());
        }
    }

    @Test void missingOrFabricatedConflictEvidenceCannotBecomeFinalContradiction() {
        for (boolean missing : List.of(false, true)) try (Fixture f = new Fixture()) {
            Spec spec = f.add(Perspective.CONFLICT_CANDIDATE, CONFLICT);
            spec.check = missing ? null : confirmedConflict(ORIGINAL, "본문에 없는 50% 수치");
            assertEquals(ReportVerdict.UNCONFIRMED, f.run().crossReports().getFirst().verdict());
            assertFalse(f.candidate(spec.url).conflictResolution().accepted());
        }
    }

    @Test void historicalDirectCandidateAtEndOfSearchPoolIsFetchedBeforeUnrelatedNextYearAnnouncements() {
        try (Fixture f = new Fixture()) {
            f.publishedAt = Instant.parse("2025-02-25T00:00:00Z");
            f.originalBody = "한국은행은 2월 25일 경제전망을 발표하고 올해 성장률 1.5%, 내년 1.8%를 종전과 같이 유지했다.";
            f.claimText = "한국은행은 올해 성장률 1.5%, 내년 1.8%를 종전과 같이 유지했다.";
            f.exactQuery = "한국은행 올해 성장률"; f.expandedQuery = "한국은행 내년 경제전망";
            f.issue = new IssueFrame("경제전망", "한국은행", "성장률", "올해", "성장률", f.originalBody, "2월 25일");
            f.fullRoutes = true;
            for (int i = 0; i < 29; i++) {
                Spec noise = f.add(Perspective.IRRELEVANT, "후속 연도의 다른 전망 발표입니다. " + i);
                f.metadata.put(noise.url, new SearchClient.Hit(noise.url, "한국은행 2026년 2월 25일 경제전망 발표",
                    "올해 1.9%, 내년 2.1% 성장률 전망", "2026-02-25"));
            }
            Spec direct = f.add(Perspective.SUPPORT_CANDIDATE,
                "한국은행은 2025년 성장률 1.5%, 2026년 1.8%를 종전 전망과 같이 유지했다.");
            direct.matchCheck = new MatchCheck(ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME,
                f.originalBody, direct.quote, "동일 발표와 기간의 종전 전망치 유지가 본문에서 확인됩니다.");
            f.metadata.put(direct.url, new SearchClient.Hit(direct.url, "2025년 2월 25일 한국은행 경제전망",
                "올해 1.5%, 내년 1.8% 종전 전망 유지", "2025-02-25"));
            CrossReport result = f.run().crossReports().getFirst();
            assertEquals(ReportVerdict.MATCHED, result.verdict());
            assertEquals(List.of(direct.url), f.fetched);
            assertTrue(f.fetched.size() <= 10); assertTrue(result.comparisons().size() <= 5);
            assertEquals(30, f.trace().candidates().size());
            assertTrue(f.trace().candidates().stream().allMatch(c -> c.prefetchPriority() != null));
            assertEquals("search-6-candidate-5", f.candidate(direct.url).candidateId());
            assertEquals(1, f.candidate(direct.url).prefetchPriority().rank());
            assertTrue(f.candidate(direct.url).matchResolution().accepted());
            assertEquals(29, f.trace().candidates().stream().filter(c -> c.events().stream()
                .anyMatch(e -> e.code().equals("PREFETCH_REJECTED"))).count());
            for (SearchTrace request : f.trace().searches()) {
                assertFalse(request.query().contains("올해")); assertFalse(request.query().contains("내년"));
                if (request.queryType() == QueryType.NEUTRAL || request.queryType() == QueryType.CHALLENGE) {
                    assertTrue(request.query().contains("2025년")); assertTrue(request.query().contains("2026년"));
                    assertTrue(request.query().contains("2월 25일")); assertTrue(request.query().contains("1.5%"));
                }
            }
        }
    }

    @Test void matchingNumbersWithoutVerifiedMaintenanceAreWithheldFromFinalMatchedResult() {
        for (boolean missingCheck : List.of(true, false)) try (Fixture f = new Fixture()) {
            f.issue = null;
            f.claimText = "성장률 전망을 종전과 같이 1.5%로 유지했다.";
            f.originalBody = f.claimText;
            Spec spec = f.add(Perspective.SUPPORT_CANDIDATE, "성장률을 1.5%로 전망했다.");
            if (!missingCheck) spec.matchCheck = new MatchCheck(ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME,
                ScopeMatch.SAME, ScopeMatch.SAME, f.originalBody, spec.quote, "모델이 수치 일치만으로 유지했다고 잘못 판단한 응답");
            CrossReport result = f.run().crossReports().getFirst();
            assertEquals(ReportVerdict.UNCONFIRMED, result.verdict());
            assertEquals(0, result.matchingCount());
            assertEquals("MATCHES", f.candidate(spec.url).comparison().stance());
            assertFalse(f.candidate(spec.url).matchResolution().accepted());
            assertTrue(f.candidate(spec.url).usedQuotes().isEmpty());
        }
    }

    private static final class Spec {
        final String url, quote;
        final Perspective perspective;
        String duplicate = "", originQuote = "";
        ConflictCheck check;
        MatchCheck matchCheck;
        Spec(String url, Perspective perspective, String quote) {
            this.url = url; this.perspective = perspective; this.quote = quote;
            this.check = confirmedConflict(ORIGINAL, quote);
        }
        CandidateClaim analysis(String id, Map<String, String> ids) {
            return new CandidateClaim(id, perspective, quote, quote, "본문에 근거한 후보 분류이며 최종 판정이 아닙니다.",
                duplicate.isEmpty() ? "" : ids.get(duplicate), originQuote);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JsonMapper json = JsonMapper.builder().build();
        final DocumentFetcher fetcher = mock(DocumentFetcher.class);
        final SearchClient search = mock(SearchClient.class);
        final StructuredLlm llm = mock(StructuredLlm.class);
        final AnalysisContext context = new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(600), ignored -> {});
        final AnalysisPipeline pipeline = new AnalysisPipeline(fetcher, search, llm, new SafeUrlPolicy(), json);
        final LinkedHashMap<String, Spec> specs = new LinkedHashMap<>();
        final Map<String, String> bodies = new HashMap<>();
        final Map<String, SearchClient.Hit> metadata = new HashMap<>();
        final List<String> fetched = new ArrayList<>();
        IssueFrame issue = ISSUE;
        String originalBody = ORIGINAL;
        String claimText = "2027년 복지 예산 감소율은 12%다.", exactQuery = "원문 주장 12%", expandedQuery = "복지 예산 변화";
        Instant publishedAt;
        boolean badQuote, omitCandidate, perspectiveFailure, fetchFailure, fullRoutes, duplicateQueries;
        Fixture() {
            when(fetcher.fetch(anyString(), eq(true), same(context))).thenAnswer(i ->
                new Document("https://original.example.com/a", "원문", "원본일보", null, publishedAt, originalBody, false));
            when(fetcher.fetch(anyString(), eq(false), same(context))).thenAnswer(i -> {
                String url = i.getArgument(0); fetched.add(url);
                if (fetchFailure) throw new AnalysisFailure("ARTICLE_FETCH_FAILED", "원문 수집 실패");
                return new Document(url, "반박인가?", "언론사 " + url, null,
                    publishedAt == null ? Instant.parse("2026-11-01T00:00:00Z") : publishedAt,
                    bodies.getOrDefault(url, specs.get(url).quote + " 별도 취재 배경 " + url), false);
            });
            when(search.search(any(), anyString(), eq(5), same(context))).thenAnswer(i -> {
                SearchClient.Provider provider = i.getArgument(0); String query = i.getArgument(1);
                int route = query.equals(ReportRetrievalPolicy.normalizeYears(exactQuery, publishedAt)) ? (provider == NAVER ? 0 : 1)
                    : query.equals(ReportRetrievalPolicy.normalizeYears(expandedQuery, publishedAt)) ? (provider == NAVER ? 2 : 3)
                    : query.endsWith(" 정정") ? 5 : 4;
                if (!fullRoutes && route > 1) return List.of();
                return specs.values().stream().skip(route * 5L).limit(5)
                    .map(s -> metadata.getOrDefault(s.url, new SearchClient.Hit(s.url, "검색 제목", "검색 요약", "2026-11-01"))).toList();
            });
            when(llm.generate(anyString(), anyString(), any(), any(), same(context))).thenAnswer(i -> {
                String prompt = i.getArgument(0); JsonNode input = json.readTree((String) i.getArgument(1));
                Object output;
                if (prompt.equals("extract")) output = new Extraction(
                    List.of(new DraftCrossClaim(claimText, exactQuery,
                        duplicateQueries ? exactQuery : expandedQuery, issue)), List.of());
                else if (prompt.equals("perspectives")) {
                    if (perspectiveFailure) throw new AnalysisFailure("LLM_FAILED", "응답 대기 시간 초과");
                    Map<String, String> ids = new HashMap<>();
                    for (JsonNode c : input.path("candidates")) ids.put(c.path("document").path("url").asString(), c.path("evidenceId").asString());
                    List<CandidateClaim> candidates = new ArrayList<>();
                    for (JsonNode c : input.path("candidates")) {
                        Spec s = specs.get(c.path("document").path("url").asString());
                        CandidateClaim candidate = s.analysis(c.path("evidenceId").asString(), ids);
                        if (badQuote) candidate = new CandidateClaim(candidate.evidenceId(), candidate.perspective(), "창작", "창작", "이유", "", "");
                        candidates.add(candidate);
                    }
                    if (omitCandidate) candidates.clear();
                    output = new PerspectiveSelection(candidates);
                } else if (prompt.equals("select")) {
                    List<Quote> quotes = new ArrayList<>();
                    for (JsonNode c : input.path("candidates")) {
                        if (quotes.size() == 5) break;
                        quotes.add(new Quote(c.path("evidenceId").asString(), specs.get(c.path("document").path("url").asString()).quote));
                    }
                    output = new ReportSelection(quotes);
                } else if (prompt.equals("verify")) {
                    List<ReportDecision> decisions = new ArrayList<>();
                    for (JsonNode c : input.path("articles")) {
                        Spec s = specs.get(c.path("document").path("url").asString());
                        decisions.add(new ReportDecision(c.path("evidenceId").asString(),
                            s.perspective == Perspective.CONFLICT_CANDIDATE ? DecisionStance.CONTRADICTS : DecisionStance.MATCHES,
                            "직접 사실 비교", s.perspective == Perspective.CONFLICT_CANDIDATE ? s.check : null, s.matchCheck));
                    }
                    output = new ReportReview(decisions);
                } else if (prompt.equals("summarize")) output = new OverallSummary("요약", "교차보도 요약", "정보원 없음", List.of());
                else throw new AssertionError(prompt);
                @SuppressWarnings("unchecked") Consumer<Object> validate = i.getArgument(3);
                try { validate.accept(output); }
                catch (IllegalArgumentException e) { throw new AnalysisFailure("LLM_FAILED", "모의 응답 검증 실패"); }
                return output;
            });
        }
        Spec add(Perspective perspective, String quote) {
            String url = "https://external.example.com/" + (specs.size() + 1);
            Spec spec = new Spec(url, perspective, quote); specs.put(url, spec); return spec;
        }
        Result run() { return pipeline.run(UUID.randomUUID(), "https://original.example.com/a", context); }
        ClaimTrace trace() { return context.traceSnapshot().getFirst(); }
        CandidateTrace candidate(String url) { return trace().candidates().stream().filter(c -> c.url().equals(url)).findFirst().orElseThrow(); }
        @Override public void close() { pipeline.close(); }
    }
}
