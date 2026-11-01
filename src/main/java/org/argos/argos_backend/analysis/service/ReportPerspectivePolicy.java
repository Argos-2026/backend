package org.argos.argos_backend.analysis.service;

import java.util.*;
import org.argos.argos_backend.external.document.DocumentFetcher.Document;
import org.argos.argos_backend.external.llm.LlmModels.PerspectiveSelection;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;
import static org.argos.argos_backend.analysis.service.AnalysisPipeline.*;

/** Bounded, deterministic grounding/selection gates. No network or model calls. */
final class ReportPerspectivePolicy {
    private ReportPerspectivePolicy() {}

    static void validateIssue(IssueFrame issue, Document article) {
        require(issue != null && grounded(article.body(), issue.originalQuote(), 1000));
        require(nonblank(issue.event()) && nonblank(issue.subject()));
        for (String field : List.of(issue.event(), issue.subject(), issue.target(), issue.period(), issue.metric()))
            require(field.length() <= 80 && (field.isBlank() || contains(issue.originalQuote(), field)));
        require(issue.announcementDate() == null || issue.announcementDate().isBlank()
            || (issue.announcementDate().length() <= 80 && contains(article.title() + " " + article.body(), issue.announcementDate())));
    }

    static String neutralQuery(IssueFrame issue) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        // Metric means its name, never the original answer/value; all spans come from the original quote.
        for (String value : List.of(issue.subject(), issue.target(), issue.event(), issue.period(), issue.metric())) {
            if (!value.isBlank() && !parts.stream().anyMatch(p -> p.contains(value))) parts.add(value.strip());
        }
        StringBuilder query = new StringBuilder();
        for (String part : parts) if (query.length() + part.length() + 1 <= 280) {
            if (!query.isEmpty()) query.append(' ');
            query.append(part);
        }
        return query.toString();
    }

    static void validate(PerspectiveSelection selection, List<String> ids, Map<String, Document> documents) {
        require(selection != null && selection.candidates() != null && selection.candidates().size() == ids.size());
        Map<String, CandidateClaim> indexed = new HashMap<>();
        for (CandidateClaim candidate : selection.candidates()) {
            require(candidate != null && ids.contains(candidate.evidenceId())
                && indexed.putIfAbsent(candidate.evidenceId(), candidate) == null && candidate.perspective() != null
                && nonblank(candidate.reason()) && candidate.reason().length() <= 1500
                && candidate.statement() != null && candidate.quote() != null
                && candidate.duplicateOfEvidenceId() != null && candidate.sharedOriginQuote() != null);
            Document doc = documents.get(candidate.evidenceId());
            if (eligible(candidate)) require(grounded(doc.body(), candidate.quote(), 1000)
                && grounded(candidate.quote(), candidate.statement(), 500));
            else {
                require(candidate.statement().isBlank() || grounded(doc.body(), candidate.statement(), 500));
                require(candidate.quote().isBlank() || grounded(doc.body(), candidate.quote(), 1000));
            }
        }
        for (CandidateClaim candidate : selection.candidates()) {
            if (candidate.duplicateOfEvidenceId().isBlank()) {
                require(candidate.sharedOriginQuote().isBlank());
                continue;
            }
            CandidateClaim original = indexed.get(candidate.duplicateOfEvidenceId());
            require(original != null && original != candidate && original.perspective() == candidate.perspective());
            require(candidate.sharedOriginQuote().length() >= 20
                && grounded(documents.get(candidate.evidenceId()).body(), candidate.sharedOriginQuote(), 1000)
                && grounded(documents.get(original.evidenceId()).body(), candidate.sharedOriginQuote(), 1000));
            Set<String> visited = new HashSet<>();
            CandidateClaim next = candidate;
            while (!next.duplicateOfEvidenceId().isBlank()) {
                require(visited.add(next.evidenceId()));
                next = indexed.get(next.duplicateOfEvidenceId());
                require(next != null);
            }
        }
    }

    static List<CandidateClaim> select(PerspectiveSelection selection, List<String> order, int limit) {
        Map<String, CandidateClaim> indexed = new HashMap<>();
        selection.candidates().forEach(c -> indexed.put(c.evidenceId(), c));
        List<CandidateClaim> support = new ArrayList<>(), conflict = new ArrayList<>();
        Set<String> origins = new HashSet<>();
        for (String id : order) {
            CandidateClaim c = indexed.get(id);
            if (!eligible(c)) continue;
            CandidateClaim root = c;
            while (!root.duplicateOfEvidenceId().isBlank()) root = indexed.get(root.duplicateOfEvidenceId());
            if (!origins.add(root.evidenceId())) continue;
            (root.perspective() == Perspective.CONFLICT_CANDIDATE ? conflict : support).add(root);
        }
        List<CandidateClaim> selected = new ArrayList<>();
        // Give each grounded class an opportunity, without inventing a quota when a class is absent.
        for (int i = 0; selected.size() < limit && (i < support.size() || i < conflict.size()); i++) {
            if (i < support.size()) selected.add(support.get(i));
            if (selected.size() < limit && i < conflict.size()) selected.add(conflict.get(i));
        }
        return List.copyOf(selected);
    }

    static ConflictResolution resolve(ConflictCheck check, Document original, Document external) {
        return resolve(check, original, external, null);
    }

    static ConflictResolution resolve(ConflictCheck check, Document original, Document external, IssueFrame issue) {
        if (check == null) return new ConflictResolution(null, false, "충돌 비교 조건이 누락되어 상반을 인정하지 않았습니다.");
        if (check.originalQuote() == null || check.externalQuote() == null || check.reason() == null
            || check.originalQuote().length() > 1000 || check.externalQuote().length() > 1000 || check.reason().length() > 1500)
            return new ConflictResolution(null, false, "충돌 비교 응답의 문장 또는 설명 범위가 유효하지 않습니다.");
        if (!grounded(original.body(), check.originalQuote(), 1000)
            || !grounded(external.body(), check.externalQuote(), 1000)
            || (issue != null && !contains(issue.originalQuote(), check.originalQuote())))
            return new ConflictResolution(check, false, "원문 또는 외부 기사에서 충돌 근거 문장을 확인하지 못했습니다.");
        if (check.sameEvent() != ScopeMatch.SAME || check.sameSubject() != ScopeMatch.SAME
            || check.sameTarget() != ScopeMatch.SAME || check.samePeriod() != ScopeMatch.SAME)
            return new ConflictResolution(check, false, "동일 사건·주체·대상·기간이 확인되지 않아 상반을 인정하지 않았습니다.");
        if (!comparable(check.sameMetric()) || !comparable(check.sameUnit()) || !comparable(check.sameDenominator())
            || !comparable(check.sameAggregation()) || !comparable(check.sameConditions()))
            return new ConflictResolution(check, false, "지표·단위·분모·집계 기준·조건이 다르거나 불명확합니다.");
        String quotes = check.originalQuote() + " " + check.externalQuote();
        boolean measured = java.util.regex.Pattern.compile("[0-9][0-9,.]*\\s*(%|퍼센트|원|명|건|개|회|배|톤|kg|km)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(quotes).find();
        boolean ratio = java.util.regex.Pattern.compile("[0-9][0-9,.]*\\s*(%|퍼센트)").matcher(quotes).find();
        if ((measured && (check.sameMetric() != ScopeMatch.SAME || check.sameUnit() != ScopeMatch.SAME
                || check.sameAggregation() != ScopeMatch.SAME))
            || (ratio && check.sameDenominator() != ScopeMatch.SAME))
            return new ConflictResolution(check, false, "수치 비교의 지표·단위·집계 기준 또는 비율의 분모를 해당 없음으로 생략할 수 없습니다.");
        if (!check.mutuallyExclusive() || !nonblank(check.reason()))
            return new ConflictResolution(check, false, "양립 불가능한 사실관계가 확인되지 않았습니다.");
        return new ConflictResolution(check, true, "본문 근거와 동일 비교 조건을 확인해 상반 보도로 인정했습니다. 사실의 진위 확정은 아닙니다.");
    }

    private static boolean comparable(ScopeMatch value) { return value == ScopeMatch.SAME || value == ScopeMatch.NOT_APPLICABLE; }
    static MatchResolution resolveMatch(MatchCheck check, String claim, Document original, Document external, IssueFrame issue) {
        String action = ReportRetrievalPolicy.action(claim);
        if (action.equals("NONE")) return new MatchResolution(null, true, "변경 행위가 없는 주장은 기존 직접 사실 비교를 적용했습니다.");
        if (check == null) return new MatchResolution(null, false, "변경 행위와 비교 기준의 검증 응답이 없어 일치를 인정하지 않았습니다.");
        if (!grounded(original.body(), check.originalQuote(), 1000) || !grounded(external.body(), check.externalQuote(), 1000)
            || (issue != null && !contains(issue.originalQuote(), check.originalQuote())))
            return new MatchResolution(null, false, "변경 행위를 뒷받침하는 원문·외부 본문 인용을 확인하지 못했습니다.");
        if (check.sameEvent() != ScopeMatch.SAME || check.samePeriod() != ScopeMatch.SAME || check.sameValue() != ScopeMatch.SAME
            || check.sameAction() != ScopeMatch.SAME || check.sameComparisonBasis() != ScopeMatch.SAME)
            return new MatchResolution(check, false, "동일 사건·대상 기간·수치·변경 행위·비교 기준이 모두 확인되지 않았습니다.");
        if (!ReportRetrievalPolicy.sameAction(action, check.externalQuote())
            || !ReportRetrievalPolicy.valuesCovered(claim, check.externalQuote())
            || !nonblank(check.reason()) || check.reason().length() > 1500)
            return new MatchResolution(check, false, "수치뿐 아니라 유지·상향·하향 행위와 비교 근거가 외부 인용에 필요합니다.");
        return new MatchResolution(check, true, "본문 인용에서 수치·변경 행위 및 동일 비교 기준의 일치를 확인했습니다.");
    }
    private static boolean eligible(CandidateClaim c) {
        return c.perspective() == Perspective.SUPPORT_CANDIDATE || c.perspective() == Perspective.CONFLICT_CANDIDATE;
    }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static boolean grounded(String body, String quote, int max) {
        return nonblank(quote) && quote.length() <= max && contains(body, quote);
    }
}
