package org.argos.argos_backend.external.search;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.external.http.ExternalCalls;
import org.argos.argos_backend.external.http.WebTransport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.support.AnalysisFixtures.properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline diagnostics for the existing NAVER response parser; no production search behavior changes. */
class SearchClientTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExternalCalls calls = mock(ExternalCalls.class);
    private final AnalysisContext context = new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(600), ignored -> {});
    private final SearchClient client = new SearchClient(calls, properties(100, Duration.ofMinutes(10), true), json);

    @Test void documentedItemsWithOriginalUrlsArePreservedAndRequestMatchesApiHub() {
        List<Map<String, String>> items = IntStream.rangeClosed(1, 5).mapToObj(i ->
            Map.of("originallink", "https://original.example.com/" + i, "link", "https://news.example.com/" + i,
                "title", "<b>검색</b> 제목", "description", "사용하지 않는 원문 요약", "pubDate", "Tue, 20 Oct 2026 12:00:00 +0900")).toList();
        stub(Map.of("lastBuildDate", "Tue, 20 Oct 2026 12:00:00 +0900", "total", 123,
            "start", 1, "display", 5, "items", items));
        List<SearchClient.Hit> hits = search();
        assertEquals(items.size(), hits.size());
        assertEquals("https://original.example.com/1", hits.getFirst().url());
        assertEquals("검색 제목", hits.getFirst().title());
        ArgumentCaptor<URI> requested = ArgumentCaptor.forClass(URI.class);
        verify(calls).request(requested.capture(), eq("GET"), anyMap(), isNull(), eq(Duration.ofSeconds(15)), eq("SEARCH_FAILED"), same(context));
        assertEquals("https://naverapihub.apigw.ntruss.com/search/v1/news", requested.getValue().toString().split("\\?")[0]);
        assertTrue(requested.getValue().getRawQuery().contains("display=5&sort=sim&format=json"));
    }

    @Test void linkFallbackPreservesCandidatesAndOnlyMissingUrlsAreDropped() {
        List<Map<String, String>> items = List.of(
            Map.of("originallink", "  https://original.example.com/1  ", "link", "https://news.example.com/1"),
            Map.of("originallink", " \t ", "link", "https://news.example.com/2"),
            Map.of("title", "URL 없는 항목"),
            Map.of("originallink", "", "link", " \n "));
        stub(Map.of("total", 4, "items", items));
        assertEquals(List.of(new SearchClient.Hit("https://original.example.com/1", ""),
            new SearchClient.Hit("https://news.example.com/2", "")), search());
    }

    @Test void nonemptyItemsWithoutUsableUrlsCanReturnZero() {
        List<Map<String, String>> items = List.of(Map.of("title", "URL 없음"), Map.of("originallink", "", "link", " "));
        stub(Map.of("total", 2, "items", items));
        assertEquals(2, items.size());
        assertTrue(search().isEmpty());
    }

    @Test void emptyItemsReturnZero() {
        stub(Map.of("total", 0, "items", List.of()));
        assertTrue(search().isEmpty());
    }

    @Test void missingItemsIsAParseFailureRatherThanZeroResults() {
        stub(Map.of("total", 0));
        assertEquals("SEARCH_FAILED", assertThrows(AnalysisFailure.class, this::search).code());
    }

    @Test void nonblankUrlSyntaxIsNotValidatedAtSearchParsingStage() {
        stub(Map.of("items", List.of(Map.of("originallink", "not-a-url", "link", "https://news.example.com/1"))));
        assertEquals("not-a-url", search().getFirst().url());
    }

    @Test void returnedCountIsCappedAtRequestedCountRatherThanTotalMatches() {
        List<Map<String, String>> items = IntStream.rangeClosed(1, 7)
            .mapToObj(i -> Map.of("link", "https://news.example.com/" + i)).toList();
        stub(Map.of("total", 1000, "items", items));
        assertEquals(5, search().size());
    }

    private List<SearchClient.Hit> search() {
        return client.search(SearchClient.Provider.NAVER, "현대모비스 의왕연구소 화재", 5, context);
    }
    private void stub(Object body) {
        when(calls.request(any(), eq("GET"), anyMap(), isNull(), any(), eq("SEARCH_FAILED"), same(context)))
            .thenReturn(new WebTransport.Response(200, "application/json", null, json.writeValueAsBytes(body)));
    }
}
