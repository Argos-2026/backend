package org.argos.argos_backend.analysis.domain;

import java.util.List;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.*;
import org.argos.argos_backend.external.search.SearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CrossReportTraceTests {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void nullEvidenceBeforeBetweenOrAfterSelectedCandidatesDoesNotInterruptSelection(int excludedIndex) {
        CrossReportTrace trace = new CrossReportTrace();
        trace.claim("report-1", "주장", "exact", "expanded");
        int nextEvidence = 1;
        for (int i = 0; i < 3; i++) {
            String candidateId = "candidate-" + i;
            candidate(trace, "report-1", candidateId);
            if (i != excludedIndex) trace.evidence("report-1", candidateId, "evidence-" + nextEvidence++);
        }
        List<String> eligible = List.copyOf(List.of("evidence-1", "evidence-2"));
        assertDoesNotThrow(() -> trace.selection("report-1", eligible, eligible));
        eligible.forEach(id -> trace.comparison("report-1", id, "MATCHES", "실제 비교 이유"));
        ClaimTrace claim = trace.snapshot().getFirst();
        assertEquals(eligible, claim.selectedEvidenceIds());
        for (int i = 0; i < 3; i++) {
            CandidateTrace candidate = claim.candidates().get(i);
            if (i == excludedIndex) {
                assertNull(candidate.evidenceId()); assertNull(candidate.llmSelected()); assertNull(candidate.comparison());
                assertTrue(candidate.events().isEmpty());
            } else {
                assertEquals(Boolean.TRUE, candidate.llmSelected());
                assertEquals(List.of("LLM_SELECTED", "COMPARED"), candidate.events().stream().map(CandidateEvent::code).toList());
                assertEquals(candidate.evidenceId(), candidate.comparison().evidenceId());
            }
        }
    }

    @Test void reusedEvidenceUpdatesEveryLinkedCandidateWithinEachClaimIndependently() {
        CrossReportTrace trace = new CrossReportTrace();
        for (String claimId : List.of("report-1", "report-2")) {
            trace.claim(claimId, "주장", "exact", "expanded");
            for (String candidateId : List.of("candidate-1", "candidate-2", "candidate-3")) {
                candidate(trace, claimId, candidateId);
                if (!candidateId.equals("candidate-2")) trace.evidence(claimId, candidateId, "evidence-1");
            }
        }
        assertDoesNotThrow(() -> trace.selection("report-1", List.of("evidence-1"), List.of("evidence-1")));
        assertDoesNotThrow(() -> trace.selection("report-2", List.of("evidence-1"), List.of()));
        trace.comparison("report-1", "evidence-1", "MATCHES", "주장 1 비교 이유");
        List<ClaimTrace> claims = trace.snapshot();
        assertEquals(List.of("evidence-1"), claims.get(0).selectedEvidenceIds());
        assertTrue(claims.get(1).selectedEvidenceIds().isEmpty());
        for (int i : List.of(0, 2)) {
            CandidateTrace selected = claims.get(0).candidates().get(i);
            assertEquals(Boolean.TRUE, selected.llmSelected());
            assertEquals(List.of("LLM_SELECTED", "COMPARED"), selected.events().stream().map(CandidateEvent::code).toList());
            assertEquals("주장 1 비교 이유", selected.comparison().reason());
            CandidateTrace rejected = claims.get(1).candidates().get(i);
            assertEquals(Boolean.FALSE, rejected.llmSelected());
            assertEquals(List.of("LLM_NOT_SELECTED"), rejected.events().stream().map(CandidateEvent::code).toList());
            assertNull(rejected.comparison());
        }
        for (ClaimTrace claim : claims) {
            assertNull(claim.candidates().get(1).llmSelected());
            assertTrue(claim.candidates().get(1).events().isEmpty());
        }
    }

    private static void candidate(CrossReportTrace trace, String claimId, String candidateId) {
        trace.candidate(claimId, candidateId, "search-1", SearchClient.Provider.NAVER,
            new SearchClient.Hit("https://news.example.com/" + candidateId, "검색 제목"));
    }
}
