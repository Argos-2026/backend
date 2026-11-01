package org.argos.argos_backend.external.search;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.external.http.ExternalCalls;
import org.argos.argos_backend.global.config.ArgosProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SearchClient {
    public enum Provider { NAVER, BRAVE }
    public record Hit(String url, String title, String description, String dateHint) {
        public Hit(String url, String title) { this(url, title, "", ""); }
    }
    private final ExternalCalls calls;
    private final ArgosProperties properties;
    private final JsonMapper json;
    public SearchClient(ExternalCalls calls, ArgosProperties properties, JsonMapper json) {
        this.calls = calls; this.properties = properties; this.json = json;
    }
    public List<Hit> search(Provider provider, String query, int count, AnalysisContext context) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create(provider == Provider.NAVER
            ? "https://naverapihub.apigw.ntruss.com/search/v1/news?query=" + encoded + "&display=" + count + "&sort=sim&format=json"
            : "https://api.search.brave.com/res/v1/web/search?q=" + encoded + "&count=" + count);
        Map<String, String> headers = provider == Provider.NAVER
            ? Map.of("X-NCP-APIGW-API-KEY-ID", properties.naverClientId(), "X-NCP-APIGW-API-KEY", properties.naverClientSecret())
            : Map.of("X-Subscription-Token", properties.braveApiKey(), "Accept", "application/json");
        var response = calls.request(uri, "GET", headers, null, Duration.ofSeconds(15), "SEARCH_FAILED", context);
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode items = provider == Provider.NAVER ? root.path("items") : root.path("web").path("results");
            if (!items.isArray()) {
                // Brave can omit web entirely when there are no web results.
                if (provider == Provider.BRAVE && root.has("query") && !root.has("web")) return List.of();
                throw new IllegalArgumentException();
            }
            List<Hit> hits = new ArrayList<>();
            for (JsonNode item : items) {
                String url = provider == Provider.NAVER
                    ? DocumentFetcher.first(item.path("originallink").asString(""), item.path("link").asString(""))
                    : item.path("url").asString("");
                if (url != null && !url.isBlank()) hits.add(new Hit(url,
                    org.jsoup.Jsoup.parse(item.path("title").asString("")).text(),
                    org.jsoup.Jsoup.parse(item.path("description").asString("")).text(),
                    item.path(provider == Provider.NAVER ? "pubDate" : "page_age").asString("")));
                if (hits.size() >= count) break;
            }
            return List.copyOf(hits);
        } catch (RuntimeException e) { throw new AnalysisFailure("SEARCH_FAILED", "검색 응답을 해석하지 못했습니다."); }
    }
}
