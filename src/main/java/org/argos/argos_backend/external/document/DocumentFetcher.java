package org.argos.argos_backend.external.document;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.analysis.domain.AnalysisModels;
import org.argos.argos_backend.external.http.ApacheWebTransport;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.http.WebTransport;
import org.argos.argos_backend.global.exception.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DocumentFetcher {
    public record Document(String url, String title, String publisher, String author, Instant publishedAt,
                           String body, boolean truncated) {
        public AnalysisModels.Article article() { return new AnalysisModels.Article(url, title, publisher, author, publishedAt); }
    }
    private final WebTransport transport;
    private final SafeUrlPolicy policy;
    private final JsonMapper json;
    public DocumentFetcher(WebTransport transport, SafeUrlPolicy policy, JsonMapper json) {
        this.transport = transport; this.policy = policy; this.json = json;
    }

    public Document fetch(String url, boolean article, AnalysisContext context) {
        try { return fetchChecked(url, article, context); }
        catch (ApiException e) { throw fetchFailure(); }
    }

    private Document fetchChecked(String url, boolean article, AnalysisContext context) {
        URI uri = policy.validate(url);
        for (int redirects = 0; redirects <= 5; redirects++) {
            context.check();
            WebTransport.Response response = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    response = transport.exchange(uri, "GET", Map.of("Accept", "text/html,application/pdf"), null,
                        context.timeout(Duration.ofSeconds(15)), 5 * 1024 * 1024);
                    if (response.status() != 429 && response.status() < 500) break;
                } catch (ApacheWebTransport.TransportFailure e) { response = null; }
                context.check();
            }
            if (response == null) throw fetchFailure();
            if (Set.of(301, 302, 303, 307, 308).contains(response.status())) {
                if (response.location() == null || redirects == 5) throw fetchFailure();
                try { uri = policy.validate(uri.resolve(response.location()).toString()); }
                catch (IllegalArgumentException e) { throw fetchFailure(); }
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) throw fetchFailure();
            try { return parse(uri.toString(), response.contentType(), response.body(), article); }
            catch (AnalysisFailure e) { throw e; }
            catch (Exception e) { throw unavailable(); }
        }
        throw fetchFailure();
    }

    Document parse(String url, String contentType, byte[] bytes, boolean article) throws Exception {
        if (contentType.toLowerCase(Locale.ROOT).contains("pdf") || (bytes.length > 4 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF"))) {
            if (article) throw unavailable();
            try (var pdf = Loader.loadPDF(bytes)) {
                if (!pdf.getCurrentAccessPermission().canExtractContent()) throw unavailable();
                var stripper = new PDFTextStripper();
                stripper.setEndPage(Math.min(50, pdf.getNumberOfPages()));
                String body = clean(stripper.getText(pdf));
                if (body.length() < 30) throw unavailable();
                String title = pdf.getDocumentInformation().getTitle();
                return new Document(url, title == null || title.isBlank() ? "PDF 자료" : title, null, null, null,
                    limit(body), body.length() > 30000 || pdf.getNumberOfPages() > 50);
            }
        }
        if (!(contentType.toLowerCase(Locale.ROOT).contains("html") || contentType.isBlank())) throw unavailable();
        var html = Jsoup.parse(new ByteArrayInputStream(bytes), null, url);
        JsonNode news = null;
        for (Element script : html.select("script[type=application/ld+json]")) {
            try { news = findArticle(json.readTree(script.data())); if (news != null) break; }
            catch (RuntimeException ignored) { /* Invalid site metadata falls back to HTML. */ }
        }
        String title = first(field(news, "headline"), meta(html, "og:title"), html.select("h1").text(), html.title());
        String host = URI.create(url).getHost();
        boolean naverNews = "news.naver.com".equalsIgnoreCase(host) || "n.news.naver.com".equalsIgnoreCase(host);
        String publisher = first(
            naverNews ? html.select(".media_end_head_top_press").text() : null,
            naverNews ? html.select(".media_end_head_top_logo img").attr("alt") : null,
            nestedName(news, "publisher"), meta(html, "og:site_name"));
        String author = first(
            naverNews ? html.select(".media_end_head_journalist_name").text() : null,
            nestedName(news, "author"), meta(html, "author"), html.select("[rel=author]").text());
        Instant published = parseDate(first(field(news, "datePublished"), meta(html, "article:published_time"), meta(html, "date")));
        String jsonBody = first(field(news, "articleBody"));
        html.select("script,style,nav,header,footer,aside,form,[role=navigation],.advertisement,.ad,.related,.comments").remove();
        Element main = html.selectFirst("#dic_area,#newsct_article,#articleBodyContents,#articleBody,#article-body,[itemprop=articleBody],article,main");
        String body;
        if (jsonBody != null) body = clean(Jsoup.parse(jsonBody).text());
        else if (main != null) body = clean(main.text());
        else body = clean(html.select("p").text());
        if (title == null || body.length() < (article ? 100 : 30)) throw unavailable();
        return new Document(url, title, publisher, author, published, limit(body), body.length() > 30000);
    }

    private JsonNode findArticle(JsonNode node) {
        if (node.isArray()) { for (JsonNode item : node) { var found = findArticle(item); if (found != null) return found; } }
        if (node.isObject()) {
            String type = node.path("@type").toString();
            if (type.contains("NewsArticle") || type.contains("ReportageNewsArticle") || type.contains("Article")) return node;
            if (node.has("@graph")) return findArticle(node.get("@graph"));
        }
        return null;
    }
    private String field(JsonNode n, String key) { return n == null || !n.path(key).isString() ? null : n.path(key).asString(); }
    private String nestedName(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode value = n.path(key);
        if (value.isArray() && !value.isEmpty()) value = value.get(0);
        return value.isString() ? value.asString() : field(value, "name");
    }
    private String meta(org.jsoup.nodes.Document html, String key) {
        Element el = html.selectFirst("meta[property='" + key + "'],meta[name='" + key + "']");
        return el == null ? null : el.attr("content");
    }
    public static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }
    public static String clean(String value) { return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip(); }
    private String limit(String text) { return text.substring(0, Math.min(30000, text.length())); }
    static Instant parseDate(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) {}
        try { return OffsetDateTime.parse(value).toInstant(); } catch (RuntimeException ignored) {}
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (RuntimeException ignored) {}
        // Do not invent a timezone or time when the publisher supplied only a date.
        return null;
    }
    private AnalysisFailure fetchFailure() { return new AnalysisFailure("ARTICLE_FETCH_FAILED", "문서 원문을 가져오지 못했습니다."); }
    private AnalysisFailure unavailable() { return new AnalysisFailure("ARTICLE_CONTENT_UNAVAILABLE", "분석 가능한 문서 본문을 확보하지 못했습니다."); }
}
