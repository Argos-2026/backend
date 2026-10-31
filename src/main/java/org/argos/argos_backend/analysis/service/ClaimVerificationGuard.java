package org.argos.argos_backend.analysis.service;

import java.util.*;
import org.argos.argos_backend.external.document.DocumentFetcher;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.external.llm.LlmModels.*;

/** Keeps statement attribution separate from the truth of the statement's contents. */
final class ClaimVerificationGuard {
    private ClaimVerificationGuard() {}

    static VerifiedClaim enforce(DraftClaim draft, VerifiedClaim verified) {
        Set<String> attributionIds = new HashSet<>();
        if (draft.kind() == ClaimKind.ATTRIBUTION) {
            for (Quote quote : verified.attributionQuotes()) {
                if (normalized(quote.quote()).contains(normalized(draft.speaker()))) attributionIds.add(quote.evidenceId());
            }
        }
        List<EvidenceLink> links = verified.evidenceLinks().stream().map(link -> {
            boolean matches = verified.targetMatched()
                && (draft.kind() == ClaimKind.FACT || attributionIds.contains(link.evidenceId()));
            return matches ? link : new EvidenceLink(link.evidenceId(), Stance.CONTEXT);
        }).toList();
        Set<Stance> stances = new HashSet<>();
        links.forEach(link -> stances.add(link.stance()));
        boolean decisive = switch (verified.verdict()) {
            case SUPPORT -> stances.contains(Stance.SUPPORTS);
            case REFUTE -> stances.contains(Stance.REFUTES);
            case CONFLICT -> stances.contains(Stance.SUPPORTS) && stances.contains(Stance.REFUTES);
            case INSUFFICIENT -> true;
        };
        if (verified.verdict() != Verdict.INSUFFICIENT && (!verified.targetMatched() || !decisive)) {
            String reason = draft.kind() == ClaimKind.ATTRIBUTION
                ? "확보한 자료만으로 " + draft.speaker() + "의 해당 발언 여부를 확인할 수 없습니다. 발언 내용에 관한 자료와 실제 발언 확인은 구분해야 합니다."
                : "확보한 자료가 주장의 일부 내용 또는 다른 검증 대상을 다루고 있어, 주장 전체를 판정할 근거가 부족합니다.";
            return new VerifiedClaim(verified.claimId(), Verdict.INSUFFICIENT, reason,
                links.stream().map(link -> new EvidenceLink(link.evidenceId(), Stance.CONTEXT)).toList(), false, List.of());
        }
        List<Quote> quotes = draft.kind() == ClaimKind.ATTRIBUTION
            ? verified.attributionQuotes().stream().filter(q -> attributionIds.contains(q.evidenceId())).toList() : List.of();
        return new VerifiedClaim(verified.claimId(), verified.verdict(), verified.reason(), links, verified.targetMatched(), quotes);
    }

    private static String normalized(String value) { return DocumentFetcher.clean(value).toLowerCase(Locale.ROOT); }
}
