package org.argos.argos_backend.external.document;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.http.WebTransport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentFetcherTests {
    private final DocumentFetcher fetcher = new DocumentFetcher(mock(WebTransport.class), new SafeUrlPolicy(), JsonMapper.builder().build());

    @Test void extractsJsonLdAndRemovesHtmlClutter() throws Exception {
        String body = "공식 자료에 따른 기사 본문입니다. 주요 수치와 발표 내용이 포함되어 있습니다. ".repeat(5);
        var doc = parse("""
            <html><head><title>fallback</title><script type="application/ld+json">
            {"@graph":[{"@type":"NewsArticle","headline":"기사 제목","publisher":{"name":"언론사"},
            "author":[{"name":"기자"}],"datePublished":"2026-10-31T09:00:00+09:00"}]}
            </script></head><body><nav>메뉴 광고</nav><article>%s<aside>관련 기사</aside></article><footer>푸터</footer></body></html>
            """.formatted(body));
        assertEquals("기사 제목", doc.title());
        assertEquals("언론사", doc.publisher());
        assertEquals("기자", doc.author());
        assertEquals("2026-10-31T00:00:00Z", doc.publishedAt().toString());
        assertFalse(doc.body().contains("광고"));
        assertFalse(doc.body().contains("관련 기사"));
    }
    @Test void missingMetadataStaysNullAndLongArticlesAreMarked() throws Exception {
        var doc = parse("<html><head><title>제목</title></head><body><article>" + "긴 기사 본문입니다. ".repeat(3000) + "</article></body></html>");
        assertNull(doc.publisher());
        assertNull(doc.author());
        assertNull(doc.publishedAt());
        assertTrue(doc.truncated());
        assertEquals(30000, doc.body().length());
    }
    @Test void extractsPublisherAndAuthorFromNaverNewsHeader() throws Exception {
        String html = """
            <html><head><title>올해 수출 기사</title></head><body>
            <a class="media_end_head_top_logo" href="https://www.yna.co.kr/">
              <img alt="연합뉴스"><span class="media_end_head_top_press">연합뉴스</span>
            </a>
            <em class="media_end_head_journalist_name">신창용 기자</em>
            <div id="dic_area">%s</div></body></html>
            """.formatted("올해 수출에 관한 기사 본문입니다. ".repeat(10));
        var doc = fetcher.parse("https://n.news.naver.com/mnews/article/001/0016413780?rc=N&ntype=RANKING",
            "text/html", html.getBytes(StandardCharsets.UTF_8), true);
        assertEquals("연합뉴스", doc.publisher());
        assertEquals("신창용 기자", doc.author());
    }
    @Test void prefersNaverNewsHeaderOverPortalMetadata() throws Exception {
        String html = """
            <html><head><title>기사 제목</title><meta property="og:site_name" content="네이버 뉴스">
            <script type="application/ld+json">{"@type":"NewsArticle","publisher":{"name":"네이버 뉴스"},
            "author":{"name":"편집부"}}</script></head><body>
            <a class="media_end_head_top_logo"><img alt="연합뉴스"></a>
            <em class="media_end_head_journalist_name">신창용 기자</em>
            <div id="dic_area">%s</div></body></html>
            """.formatted("올해 수출에 관한 기사 본문입니다. ".repeat(10));
        var doc = fetcher.parse("https://news.naver.com/article/001/0016413780", "text/html",
            html.getBytes(StandardCharsets.UTF_8), true);
        assertEquals("연합뉴스", doc.publisher());
        assertEquals("신창용 기자", doc.author());
    }
    @Test void unreadableArticleIsNotReplacedByNavigation() {
        assertThrows(AnalysisFailure.class, () -> parse("<html><head><title>로그인</title></head><body><nav>메뉴</nav><main>로그인이 필요합니다.</main></body></html>"));
    }
    @Test void extractsTextPdfAndRejectsImageOnlyPdfAndPdfArticle() throws Exception {
        byte[] textPdf;
        try (PDDocument pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Official budget decreased by twelve percent compared to the previous year."); content.endText();
            }
            pdf.save(output); textPdf = output.toByteArray();
        }
        var doc = fetcher.parse("https://example.com/a.pdf", "application/pdf", textPdf, false);
        assertTrue(doc.body().contains("twelve percent"));
        assertThrows(AnalysisFailure.class, () -> fetcher.parse("https://example.com/a.pdf", "application/pdf", textPdf, true));
        try (PDDocument pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(output);
            assertThrows(AnalysisFailure.class, () -> fetcher.parse("https://example.com/scan.pdf", "application/pdf", output.toByteArray(), false));
        }
    }
    @Test void excludesUnsupportedFormats() throws Exception {
        assertThrows(AnalysisFailure.class, () -> fetcher.parse("https://example.com/a", "application/octet-stream", new byte[5], false));
    }
    private DocumentFetcher.Document parse(String html) throws Exception {
        return fetcher.parse("https://example.com/a", "text/html", html.getBytes(StandardCharsets.UTF_8), true);
    }
}
