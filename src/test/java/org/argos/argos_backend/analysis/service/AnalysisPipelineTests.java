package org.argos.argos_backend.analysis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisPipelineTests {
    @Test void deduplicatesReprintsAndNormalizesTrackingUrls() {
        String story = "이것은 동일한 기사 본문이며 원문 기사와 재게시 기사에 공통으로 실린 내용입니다. ".repeat(5);
        assertTrue(AnalysisPipeline.sameContent(story, "  " + story + "\n"));
        assertFalse(AnalysisPipeline.sameContent(story, "별개의 원문 자료로 전혀 다른 내용을 담고 있습니다. ".repeat(10)));
        assertEquals("https://example.com/a?id=1", AnalysisPipeline.canonical("https://example.com/a?utm_source=x&id=1&fbclid=y#section"));
    }
}
