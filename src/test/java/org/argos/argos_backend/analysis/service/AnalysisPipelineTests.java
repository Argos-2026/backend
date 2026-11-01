package org.argos.argos_backend.analysis.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.llm.StructuredLlm;
import org.argos.argos_backend.external.search.SearchClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.external.llm.LlmModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisPipelineTests {
    @Test void hidesInternalIdsFromUserFacingSummaryText() {
        assertEquals("경찰의 병원 압수수색", AnalysisPipeline.hideInternalSummaryReferences(
            "report-1(경찰의 병원 압수수색)"));
        assertEquals("기관의 설명과 비교했습니다.", AnalysisPipeline.hideInternalSummaryReferences(
            "source-claim-1(기관의 설명)과 비교했습니다."));
        assertEquals("관련 결과를 확인했습니다.", AnalysisPipeline.hideInternalSummaryReferences(
            "관련 결과를 확인했습니다. evidence-1"));
        assertFalse(AnalysisPipeline.hideInternalSummaryReferences(
            "report-1 source-claim-1 source-1-1 evidence-1").matches(".*(?:report|source|evidence)-\\d+.*"));
    }

    @Test void deduplicatesReprintsAndNormalizesTrackingUrls() {
        String story = "이것은 동일한 기사 본문이며 원문 기사와 재게시 기사에 공통으로 실린 내용입니다. ".repeat(5);
        assertTrue(AnalysisPipeline.sameContent(story, "  " + story + "\n"));
        assertFalse(AnalysisPipeline.sameContent(story, "별개의 원문 자료로 전혀 다른 내용을 담고 있습니다. ".repeat(10)));
        assertEquals("https://example.com/a?id=1", AnalysisPipeline.canonical("https://example.com/a?utm_source=x&id=1&fbclid=y#section"));
    }

    @Test void preservesCompletedReportWhenTimeRunsLowBeforeSourceAssessment() {
        Instant started = Instant.parse("2026-11-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(started);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        AnalysisContext context = new AnalysisContext(clock, started.plus(Duration.ofMinutes(10)), ignored -> {});
        DocumentFetcher fetcher = mock(DocumentFetcher.class);
        String citation = "기관은 내년도 복지 예산을 조정한다고 설명했다.";
        when(fetcher.fetch(anyString(), eq(true), same(context))).thenReturn(
            new DocumentFetcher.Document("https://news.example.com/a", "제목", "원본일보", null, null,
                citation + " 기사 본문과 관련 배경을 설명하는 문장입니다. ".repeat(6), false));
        SearchClient search = mock(SearchClient.class);
        when(search.search(any(), anyString(), eq(5), same(context))).thenAnswer(ignored -> {
            now.set(started.plus(Duration.ofMinutes(9)));
            return List.of();
        });
        StructuredLlm llm = mock(StructuredLlm.class);
        when(llm.generate(anyString(), anyString(), any(), any(), same(context))).thenAnswer(invocation -> {
            assertEquals("extract", invocation.getArgument(0));
            Extraction extraction = new Extraction(
                List.of(new DraftCrossClaim("복지 예산을 조정한다.", "복지 예산 조정", "복지 사업 변경")),
                List.of(new DraftSourceClaim("기관이 예산을 조정한다.", List.of(new DraftSource(
                    "기관", "", SourceType.ORGANIZATION, citation, true, "기관 소속", "기관 예산 발표")))));
            @SuppressWarnings("unchecked") Consumer<Object> validator = invocation.getArgument(3);
            validator.accept(extraction);
            return extraction;
        });
        AnalysisPipeline pipeline = new AnalysisPipeline(fetcher, search, llm, new SafeUrlPolicy(),
            JsonMapper.builder().build());
        try {
            Result result = pipeline.run(UUID.randomUUID(), "https://news.example.com/a", context);
            assertEquals(Status.PARTIAL_COMPLETED, result.status());
            assertEquals(ReportVerdict.UNCONFIRMED, result.crossReports().getFirst().verdict());
            assertTrue(result.sourceAssessments().getFirst().sources().getFirst().assessmentFailed());
            assertTrue(result.warnings().stream().anyMatch(w -> w.code().equals("ANALYSIS_TIMEOUT")));
        } finally {
            pipeline.close();
        }
    }

    @Test void independentSearchRequestsRunConcurrently() {
        AnalysisContext context = new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(600), ignored -> {});
        DocumentFetcher fetcher = mock(DocumentFetcher.class);
        when(fetcher.fetch(anyString(), eq(true), same(context))).thenReturn(
            new DocumentFetcher.Document("https://news.example.com/a", "제목", "원본일보", null, null,
                "기사 본문과 배경을 충분히 설명하는 문장입니다. ".repeat(8), false));
        CountDownLatch started = new CountDownLatch(4);
        AtomicBoolean parallel = new AtomicBoolean();
        SearchClient search = mock(SearchClient.class);
        when(search.search(any(), anyString(), eq(5), same(context))).thenAnswer(ignored -> {
            started.countDown();
            if (started.await(2, TimeUnit.SECONDS)) parallel.set(true);
            return List.of();
        });
        StructuredLlm llm = mock(StructuredLlm.class);
        when(llm.generate(anyString(), anyString(), any(), any(), same(context))).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0).equals("extract")
                ? new Extraction(List.of(new DraftCrossClaim("복지 예산을 조정한다.", "복지 예산 조정", "복지 사업 변경")),
                    List.of())
                : new OverallSummary("요약", "비교 기사 없음", "정보원 없음", List.of());
            @SuppressWarnings("unchecked") Consumer<Object> validator = invocation.getArgument(3);
            validator.accept(value);
            return value;
        });
        AnalysisPipeline pipeline = new AnalysisPipeline(fetcher, search, llm, new SafeUrlPolicy(),
            JsonMapper.builder().build());
        try {
            Result result = pipeline.run(UUID.randomUUID(), "https://news.example.com/a", context);
            assertEquals(Status.COMPLETED, result.status());
            assertTrue(parallel.get(), "Four independent provider/query searches should overlap");
        } finally {
            pipeline.close();
        }
    }
}
