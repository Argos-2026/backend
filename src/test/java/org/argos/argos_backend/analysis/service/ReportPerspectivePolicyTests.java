package org.argos.argos_backend.analysis.service;

import java.util.*;
import org.argos.argos_backend.external.document.DocumentFetcher.Document;
import org.argos.argos_backend.external.llm.LlmModels.PerspectiveSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;
import static org.argos.argos_backend.support.AnalysisFixtures.confirmedConflict;
import static org.junit.jupiter.api.Assertions.*;

class ReportPerspectivePolicyTests {
    private static final String ORIGINAL = "동일 대상의 동일 기간 지표는 12%이다.";
    private static final String EXTERNAL = "동일 대상의 동일 기간 지표는 20%이다.";
    private static Document doc(String body) { return new Document("https://example.com/a", "제목", "언론", null, null, body, false); }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void everyComparisonDimensionMustBeKnownAndComparable(int dimension) {
        for (ScopeMatch value : List.of(ScopeMatch.UNKNOWN, ScopeMatch.DIFFERENT)) {
            ScopeMatch[] scopes = new ScopeMatch[9]; Arrays.fill(scopes, ScopeMatch.SAME); scopes[dimension] = value;
            ConflictCheck check = check(scopes, true);
            assertFalse(ReportPerspectivePolicy.resolve(check, doc(ORIGINAL), doc(EXTERNAL)).accepted());
        }
    }

    @Test void compatibleFactsAndMissingOriginalGroundingCannotPassGate() {
        ScopeMatch[] scopes = new ScopeMatch[9]; Arrays.fill(scopes, ScopeMatch.SAME);
        assertFalse(ReportPerspectivePolicy.resolve(check(scopes, false), doc(ORIGINAL), doc(EXTERNAL)).accepted());
        assertFalse(ReportPerspectivePolicy.resolve(confirmedConflict("원문에 없는 문장", EXTERNAL), doc(ORIGINAL), doc(EXTERNAL)).accepted());
        assertFalse(ReportPerspectivePolicy.resolve(confirmedConflict(ORIGINAL, ""), doc(ORIGINAL), doc(EXTERNAL)).accepted());
    }

    @Test void notApplicableIsOnlyAllowedForMetricAndConditionDimensions() {
        ScopeMatch[] scopes = new ScopeMatch[9]; Arrays.fill(scopes, ScopeMatch.SAME);
        for (int i = 4; i < scopes.length; i++) scopes[i] = ScopeMatch.NOT_APPLICABLE;
        assertFalse(ReportPerspectivePolicy.resolve(check(scopes, true), doc(ORIGINAL), doc(EXTERNAL)).accepted());
        ConflictCheck nonNumeric = new ConflictCheck(scopes[0], scopes[1], scopes[2], scopes[3], scopes[4], scopes[5], scopes[6], scopes[7], scopes[8],
            true, "정책을 시행했다.", "정책을 시행하지 않았다.", "같은 조건에서 시행 여부가 다릅니다.");
        assertTrue(ReportPerspectivePolicy.resolve(nonNumeric, doc("정책을 시행했다."), doc("정책을 시행하지 않았다.")).accepted());
        for (int i = 0; i < 4; i++) {
            scopes[i] = ScopeMatch.NOT_APPLICABLE;
            assertFalse(ReportPerspectivePolicy.resolve(check(scopes, true), doc(ORIGINAL), doc(EXTERNAL)).accepted());
            scopes[i] = ScopeMatch.SAME;
        }
    }

    @Test void fabricatedStatementsCannotHideBehindGenuineQuotes() {
        CandidateClaim c = new CandidateClaim("e1", Perspective.CONFLICT_CANDIDATE, "만들어낸 99%", EXTERNAL, "이유", "", "");
        assertThrows(IllegalArgumentException.class, () -> ReportPerspectivePolicy.validate(
            new PerspectiveSelection(List.of(c)), List.of("e1"), Map.of("e1", doc(EXTERNAL))));
    }

    @Test void keywordOverlapAndCyclesCannotEstablishSharedOriginalSource() {
        String origin = "동일한 원자료를 구체적으로 지목하는 충분히 긴 문장입니다.";
        CandidateClaim first = new CandidateClaim("e1", Perspective.SUPPORT_CANDIDATE, ORIGINAL, ORIGINAL, "이유", "e2", origin);
        CandidateClaim second = new CandidateClaim("e2", Perspective.SUPPORT_CANDIDATE, ORIGINAL, ORIGINAL, "이유", "e1", origin);
        assertThrows(IllegalArgumentException.class, () -> ReportPerspectivePolicy.validate(
            new PerspectiveSelection(List.of(first, second)), List.of("e1", "e2"),
            Map.of("e1", doc(ORIGINAL + origin), "e2", doc(ORIGINAL + origin))));
        CandidateClaim keyword = new CandidateClaim("e2", Perspective.SUPPORT_CANDIDATE, ORIGINAL, ORIGINAL, "이유", "e1", "동일 대상");
        CandidateClaim root = new CandidateClaim("e1", Perspective.SUPPORT_CANDIDATE, ORIGINAL, ORIGINAL, "이유", "", "");
        assertThrows(IllegalArgumentException.class, () -> ReportPerspectivePolicy.validate(
            new PerspectiveSelection(List.of(root, keyword)), List.of("e1", "e2"), Map.of("e1", doc(ORIGINAL), "e2", doc(ORIGINAL))));
    }

    @Test void sharedSourceUsesDeclaredRepresentativeEvenWhenItWasFetchedLater() {
        String origin = "같은 사건의 원보도 자료를 구체적으로 명시하는 충분한 인용문입니다.";
        CandidateClaim first = new CandidateClaim("e1", Perspective.SUPPORT_CANDIDATE, ORIGINAL, ORIGINAL, "이유", "e2", origin);
        CandidateClaim representative = new CandidateClaim("e2", Perspective.SUPPORT_CANDIDATE, ORIGINAL, ORIGINAL, "이유", "", "");
        PerspectiveSelection selection = new PerspectiveSelection(List.of(first, representative));
        ReportPerspectivePolicy.validate(selection, List.of("e1", "e2"), Map.of("e1", doc(ORIGINAL + origin), "e2", doc(ORIGINAL + origin)));
        assertEquals(List.of(representative), ReportPerspectivePolicy.select(selection, List.of("e1", "e2"), 5));
    }

    private static ConflictCheck check(ScopeMatch[] scopes, boolean exclusive) {
        return new ConflictCheck(scopes[0], scopes[1], scopes[2], scopes[3], scopes[4], scopes[5], scopes[6], scopes[7], scopes[8],
            exclusive, ORIGINAL, EXTERNAL, "각 비교 조건에 관한 설명");
    }
}
