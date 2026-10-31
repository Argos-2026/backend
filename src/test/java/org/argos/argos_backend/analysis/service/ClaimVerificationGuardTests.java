package org.argos.argos_backend.analysis.service;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.external.llm.LlmModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaimVerificationGuardTests {
    private final DraftClaim speech = new DraftClaim("김민수가 예산이 30% 감소한다고 말했다.",
        "김민수가 예산이 30% 감소한다고 말했는가?", List.of("김민수 예산 30% 발언"), ClaimKind.ATTRIBUTION, "김민수");

    @Test void underlyingFactEvidenceDoesNotProveSpeakerMadeStatement() {
        var output = enforce(Verdict.SUPPORT, true, List.of(new EvidenceLink("e-1", Stance.SUPPORTS)), List.of());
        assertEquals(Verdict.INSUFFICIENT, output.verdict());
        assertEquals(Stance.CONTEXT, output.evidenceLinks().getFirst().stance());
        assertTrue(output.reason().contains("발언 여부"));
    }

    @Test void exactSpeakerQuoteCanSupportAttributionEvenWhenStatementIsFalse() {
        var proof = new Quote("e-1", "김민수는 기자회견에서 예산이 30% 감소한다고 말했다.");
        var output = enforce(Verdict.SUPPORT, true, List.of(new EvidenceLink("e-1", Stance.SUPPORTS)), List.of(proof));
        assertEquals(Verdict.SUPPORT, output.verdict());
        assertEquals(List.of(proof), output.attributionQuotes());
    }

    @Test void differentSpeakerCannotSupportAttribution() {
        var output = enforce(Verdict.SUPPORT, true, List.of(new EvidenceLink("e-1", Stance.SUPPORTS)),
            List.of(new Quote("e-1", "이영희는 예산이 30% 감소한다고 말했다.")));
        assertEquals(Verdict.INSUFFICIENT, output.verdict());
        assertEquals(Stance.CONTEXT, output.evidenceLinks().getFirst().stance());
    }

    @Test void partialTargetMatchOverridesPositiveVerdict() {
        var output = enforce(Verdict.SUPPORT, false, List.of(new EvidenceLink("e-1", Stance.SUPPORTS)),
            List.of(new Quote("e-1", "김민수가 관련 수치를 설명했다.")));
        assertEquals(Verdict.INSUFFICIENT, output.verdict());
        assertFalse(output.targetMatched());
    }

    @Test void unrelatedFactDocumentsAreKeptAsContextAlongsideValidSpeechEvidence() {
        var output = enforce(Verdict.SUPPORT, true,
            List.of(new EvidenceLink("speech", Stance.SUPPORTS), new EvidenceLink("budget", Stance.SUPPORTS)),
            List.of(new Quote("speech", "김민수는 예산이 30% 감소한다고 말했다.")));
        assertEquals(Verdict.SUPPORT, output.verdict());
        assertEquals(Stance.SUPPORTS, output.evidenceLinks().get(0).stance());
        assertEquals(Stance.CONTEXT, output.evidenceLinks().get(1).stance());
    }

    @Test void refutingStatementContentsDoesNotRefuteItsOccurrence() {
        var output = enforce(Verdict.REFUTE, true, List.of(new EvidenceLink("budget", Stance.REFUTES)), List.of());
        assertEquals(Verdict.INSUFFICIENT, output.verdict());
    }

    @Test void explicitSpeakerDenialCanRefuteAttribution() {
        var output = enforce(Verdict.REFUTE, true, List.of(new EvidenceLink("denial", Stance.REFUTES)),
            List.of(new Quote("denial", "김민수는 예산이 30% 감소한다는 발언을 한 적이 없다고 밝혔다.")));
        assertEquals(Verdict.REFUTE, output.verdict());
    }

    @Test void conflictRequiresSpeechEvidenceForBothDirections() {
        List<EvidenceLink> links = List.of(new EvidenceLink("speech", Stance.SUPPORTS), new EvidenceLink("denial", Stance.REFUTES));
        var proof = new Quote("speech", "김민수는 예산이 30% 감소한다고 말했다.");
        assertEquals(Verdict.INSUFFICIENT, enforce(Verdict.CONFLICT, true, links, List.of(proof)).verdict());
        assertEquals(Verdict.CONFLICT, enforce(Verdict.CONFLICT, true, links,
            List.of(proof, new Quote("denial", "김민수는 해당 발언을 부인했다."))).verdict());
    }

    @Test void ordinaryFactsDoNotRequireSpeakerQuotes() {
        DraftClaim fact = new DraftClaim("예산이 30% 감소했다.", "예산 감소율은?", List.of("예산 감소율"), ClaimKind.FACT, "");
        var verified = new VerifiedClaim("claim-1", Verdict.SUPPORT, "공식 자료에서 수치를 확인했습니다.",
            List.of(new EvidenceLink("budget", Stance.SUPPORTS)), true, List.of());
        assertEquals(Verdict.SUPPORT, ClaimVerificationGuard.enforce(fact, verified).verdict());
    }

    private VerifiedClaim enforce(Verdict verdict, boolean matched, List<EvidenceLink> links, List<Quote> quotes) {
        return ClaimVerificationGuard.enforce(speech, new VerifiedClaim("claim-1", verdict,
            quotes.isEmpty() || !matched ? "내용은 확인했지만 발언 자체는 확인하지 못했습니다." : "제시된 발언 원문을 대조한 판정입니다.", links, matched, quotes));
    }
}
