package org.argos.argos_backend.analysis.service;

import java.time.Instant;
import java.util.*;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.QueryType;
import org.argos.argos_backend.external.document.DocumentFetcher.Document;
import org.argos.argos_backend.external.llm.LlmModels.DraftCrossClaim;
import org.argos.argos_backend.external.search.SearchClient.Hit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportTemporalRetrievalTests {
    private static final Instant PUBLISHED = Instant.parse("2025-02-26T00:00:00Z");
    private static final String ORIGINAL = "한국은행은 2월 25일 경제전망을 발표하며 올해 성장률 1.5%, 내년 성장률 1.8%를 종전 전망과 같이 유지했다.";
    private static final IssueFrame ISSUE = new IssueFrame("경제전망", "한국은행", "성장률", "올해와 내년", "성장률", ORIGINAL, "2월 25일");
    private static final DraftCrossClaim CLAIM = new DraftCrossClaim("한국은행은 올해 성장률 1.5%, 내년 1.8%를 종전과 같이 유지했다.",
        "한국은행 올해 성장률 1.5%", "한국은행 내년 경제전망", ISSUE);
    private static Document original() { return doc(ORIGINAL, PUBLISHED); }
    private static Document doc(String body, Instant date) { return new Document("https://news.example.com/a", "한국은행 전망", "언론", null, date, body, false); }
    private static TemporalContext context() { return ReportRetrievalPolicy.context(CLAIM, original(), ISSUE); }
    private static ReportRetrievalPolicy.Candidate candidate(String id, QueryType route, String title, String description, String date) {
        return new ReportRetrievalPolicy.Candidate(id, new Hit("https://other.example.com/" + id, title, description, date), route);
    }

    @Test void relativeYearsUseOriginalPublicationAndNeverTodaysYear() {
        assertEquals("2025년 1.5%, 2026년 1.8%", ReportRetrievalPolicy.normalizeYears("올해 1.5%, 내년 1.8%", PUBLISHED));
        assertEquals("2026년 전망", ReportRetrievalPolicy.normalizeYears("내년도 전망", PUBLISHED));
        assertEquals("올해 내년", ReportRetrievalPolicy.normalizeYears("올해 내년", null));
        assertEquals("2025년", ReportRetrievalPolicy.normalizeYears("올해", Instant.parse("2024-12-31T16:00:00Z")));
        assertEquals("올해와 내년", ISSUE.period()); // Original evidence is never rewritten.
    }

    @Test void announcementAndTargetPeriodsAreSeparateAndNumericAnchorsSurviveQueries() {
        TemporalContext temporal = context();
        assertEquals("2025-02-25", temporal.announcementDate());
        assertEquals("ARTICLE_TEXT", temporal.announcementDateSource());
        assertTrue(temporal.targetPeriods().containsAll(List.of("2025년", "2026년")));
        String query = ReportRetrievalPolicy.query("한국은행 올해 내년 성장률 경제전망", temporal);
        assertTrue(query.contains("2025년")); assertTrue(query.contains("2026년"));
        assertTrue(query.contains("2월 25일")); assertTrue(query.contains("1.5%")); assertTrue(query.contains("1.8%"));
        assertTrue(query.contains("한국은행"));
        assertFalse(query.contains("올해")); assertFalse(query.contains("내년"));
        assertEquals("MAINTAIN", temporal.action());
    }

    @Test void announcementYearDoesNotBecomeStatisticalTargetYear() {
        String text = "한국은행은 2025년 2월 25일 내년 성장률을 발표했다.";
        IssueFrame issue = new IssueFrame("발표", "한국은행", "성장률", "내년", "성장률", text, "2025년 2월 25일");
        TemporalContext temporal = ReportRetrievalPolicy.context(new DraftCrossClaim(text, "q", "q2", issue), doc(text, PUBLISHED), issue);
        assertEquals("2025-02-25", temporal.announcementDate());
        assertEquals(List.of("2026년"), temporal.targetPeriods());
    }

    @Test void decimalGrowthRateIsNeverParsedAsAnAnnouncementDate() {
        TemporalContext temporal = ReportRetrievalPolicy.context(CLAIM, original(),
            new IssueFrame("경제전망", "한국은행", "성장률", "올해", "성장률", "올해 성장률 1.5%, 내년 1.8%"));
        // Context should use the original publication fallback, not January 5 or January 8.
        assertEquals("2025-02-26", temporal.announcementDate());
        assertEquals("PUBLISHED_AT_FALLBACK", temporal.announcementDateSource());
    }

    @Test void announcementDayUsesPublicationMonthAndAnchorsSurviveLongNeutralQueries() {
        String text = "한국은행은 25일 올해 성장률 1.5%를 발표했다.";
        IssueFrame issue = new IssueFrame("발표", "한국은행", "성장률", "올해", "성장률", text, "25일");
        TemporalContext temporal = ReportRetrievalPolicy.context(new DraftCrossClaim(text, "q", "q2", issue), doc(text, PUBLISHED), issue);
        assertEquals("2025-02-25", temporal.announcementDate());
        String query = ReportRetrievalPolicy.query("한국은행 " + "경제전망 ".repeat(60), temporal);
        assertTrue(query.length() <= 290);
        assertTrue(query.contains("2025년")); assertTrue(query.contains("2월 25일")); assertTrue(query.contains("1.5%"));
        assertTrue(query.contains("한국은행"));
    }

    @Test void ranksEntirePoolBeforeBudgetAndRetainsLaterCorrections() {
        List<ReportRetrievalPolicy.Candidate> hits = new ArrayList<>();
        for (int i = 0; i < 28; i++) hits.add(candidate("noise-" + i, QueryType.values()[i % 4],
            "한국은행 2026년 2월 25일 경제전망 발표", "올해 성장률 1.9%, 내년 성장률 2.1%", "2026-02-25"));
        hits.add(candidate("direct", QueryType.EXACT, "한국은행 2025년 2월 25일 경제전망",
            "올해 1.5%, 내년 1.8%로 종전 전망 유지", "2025-02-25"));
        hits.add(candidate("correction", QueryType.CHALLENGE, "2025년 2월 25일 한국은행 경제전망 정정",
            "당시 성장률 전망 수치에 대한 정정보도", "2026-08-01"));
        List<ReportRetrievalPolicy.Ranked> ranked = ReportRetrievalPolicy.rank(hits, context(), ISSUE);
        List<String> firstTen = ranked.stream().filter(r -> r.priority().eligible()).limit(10).map(r -> r.candidate().id()).toList();
        assertTrue(firstTen.contains("direct")); assertTrue(firstTen.contains("correction"));
        assertEquals(30, ranked.size());
        assertTrue(ranked.stream().filter(r -> r.candidate().id().startsWith("noise"))
            .allMatch(r -> !r.priority().eligible() && r.priority().reasons().contains("OTHER_ANNOUNCEMENT_IN_TEXT")));
    }

    @Test void publicationDateAloneNeverExcludesOrConfirmsAnEvent() {
        List<ReportRetrievalPolicy.Ranked> ranked = ReportRetrievalPolicy.rank(List.of(
            candidate("late", QueryType.CHALLENGE, "한국은행 경제전망", "성장률 관련 보도", "2026-08-01"),
            candidate("near", QueryType.NEUTRAL, "한국은행 경제전망", "성장률 관련 보도", "2025-02-25")), context(), ISSUE);
        assertTrue(ranked.stream().allMatch(r -> r.priority().eligible()));
        assertEquals(3, ranked.get(0).priority().score() - ranked.get(1).priority().score());
        assertEquals("near", ranked.get(0).candidate().id());
    }

    @Test void statisticalYearCannotBeCombinedWithAnotherAnnouncementDateToFakeEventMatch() {
        var candidate = candidate("different", QueryType.EXACT, "한국은행 2026년 2월 25일 경제전망 발표",
            "2025년 통계와 성장률 전망을 설명했다.", "2026-02-25");
        var ranked = ReportRetrievalPolicy.rank(List.of(candidate), context(), ISSUE).getFirst();
        assertFalse(ranked.priority().eligible());
        assertTrue(ranked.priority().reasons().contains("OTHER_ANNOUNCEMENT_IN_TEXT"));
    }

    @Test void undatedSearchSnippetDoesNotBorrowOriginalPublicationYear() {
        var unknown = candidate("undated", QueryType.NEUTRAL, "한국은행 올해 2월 25일 경제전망 발표", "성장률 전망", "");
        var ranked = ReportRetrievalPolicy.rank(List.of(unknown), context(), ISSUE).getFirst();
        assertTrue(ranked.priority().eligible());
        assertFalse(ranked.priority().reasons().contains("ANNOUNCEMENT_YEAR_MATCH"));
    }

    @Test void diversityDoesNotDisplaceClearlyMoreRelevantDirectCandidates() {
        List<ReportRetrievalPolicy.Candidate> hits = new ArrayList<>();
        for (QueryType type : QueryType.values()) for (int i = 0; i < 5; i++)
            hits.add(candidate(type + "-" + i, type, "한국은행 경제전망", "성장률 보도", "2026-05-01"));
        hits.add(candidate("direct", QueryType.EXACT, "2025년 2월 25일 한국은행 경제전망", "성장률 1.5% 1.8% 유지", "2025-02-25"));
        List<ReportRetrievalPolicy.Ranked> ranked = ReportRetrievalPolicy.rank(hits, context(), ISSUE);
        assertEquals("direct", ranked.getFirst().candidate().id());
        assertEquals(Set.of(QueryType.values()), new HashSet<>(ranked.stream().limit(5).map(r -> r.candidate().type()).toList()));
    }

    @Test void duplicatesAreRankedButUseOnlyOneCollectionSlotAcrossProvidersAndRoutes() {
        var first = candidate("copy", QueryType.EXACT, "한국은행 경제전망", "", "2026-01-01");
        var better = new ReportRetrievalPolicy.Candidate("better", new Hit(first.hit().url(),
            "2025년 2월 25일 한국은행 경제전망", "1.5%, 1.8%", "2025-02-25"), QueryType.CHALLENGE);
        List<ReportRetrievalPolicy.Ranked> ranked = ReportRetrievalPolicy.rank(List.of(first, better), context(), ISSUE);
        assertEquals(1, ranked.stream().filter(r -> r.priority().eligible()).count());
        assertEquals("better", ranked.getFirst().candidate().id());
        assertTrue(ranked.getLast().priority().reasons().contains("DUPLICATE_URL"));
    }

    @Test void missingActionOrComparisonBasisCannotPassMatchEvenWhenNumbersAgree() {
        String claim = "성장률을 종전과 같이 1.5%로 유지했다.";
        String numberOnly = "성장률을 1.5%로 전망했다.";
        assertFalse(ReportPerspectivePolicy.resolveMatch(null, claim, doc(claim, PUBLISHED), doc(numberOnly, PUBLISHED), null).accepted());
        assertFalse(ReportPerspectivePolicy.resolveMatch(match(claim, numberOnly), claim,
            doc(claim, PUBLISHED), doc(numberOnly, PUBLISHED), null).accepted());
        String quote = "성장률을 1.5%로 유지했다.";
        MatchCheck unknownBasis = new MatchCheck(ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME,
            ScopeMatch.UNKNOWN, claim, quote, "비교 기준 불명확");
        assertFalse(ReportPerspectivePolicy.resolveMatch(unknownBasis, claim, doc(claim, PUBLISHED), doc(quote, PUBLISHED), null).accepted());
    }

    @Test void equivalentNumericFormattingDoesNotConfusePercentAndPercentagePoints() {
        assertTrue(ReportRetrievalPolicy.valuesCovered("1.5%로 유지", "1.50 퍼센트로 유지"));
        assertFalse(ReportRetrievalPolicy.valuesCovered("1.5%p 상향", "1.5%로 상향"));
        assertFalse(ReportRetrievalPolicy.valuesCovered("-1.5%로 유지", "1.5%로 유지"));
        assertTrue(ReportRetrievalPolicy.valuesCovered("−1.50%로 유지", "-1.5 퍼센트로 유지"));
        assertFalse(ReportRetrievalPolicy.sameAction("MAINTAIN", "전망 수치는 유지하지 않았다."));
        assertTrue(ReportRetrievalPolicy.sameAction("MAINTAIN+LOWER", "올해 전망은 하향하고 내년 전망은 유지했다."));
    }

    @ParameterizedTest
    @CsvSource({"유지,MAINTAIN", "상향,RAISE", "하향,LOWER"})
    void matchingValueActionAndBasisWithGroundedQuotesCanPass(String verb, String action) {
        String text = "성장률을 종전 전망과 비교해 1.5%로 " + verb + "했다.";
        assertEquals(action, ReportRetrievalPolicy.action(text));
        assertTrue(ReportPerspectivePolicy.resolveMatch(match(text, text), text, doc(text, PUBLISHED), doc(text, PUBLISHED), null).accepted());
        assertFalse(ReportPerspectivePolicy.resolveMatch(match(text, text.replace("1.5%", "1.8%")), text,
            doc(text, PUBLISHED), doc(text.replace("1.5%", "1.8%"), PUBLISHED), null).accepted());
    }

    private static MatchCheck match(String original, String external) {
        return new MatchCheck(ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME,
            original, external, "동일 발표·대상 기간·수치·변경 행위·종전 비교 기준을 본문에서 확인했습니다.");
    }
}
