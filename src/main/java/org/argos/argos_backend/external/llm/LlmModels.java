package org.argos.argos_backend.external.llm;

import java.util.List;
import java.util.Map;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

public final class LlmModels {
    private LlmModels() {}

    public record DraftCrossClaim(String text, String exactQuery, String expandedQuery) {}
    public record DraftSource(String name, String affiliation, SourceType type, String citedInArticle,
                              boolean identifiable, String identityQuery, String quoteQuery) {}
    public record DraftSourceClaim(String text, List<DraftSource> sources) {}
    public record Extraction(List<DraftCrossClaim> crossReportClaims, List<DraftSourceClaim> sourceClaims) {}
    public record Quote(String evidenceId, String quote) {}
    public record ReportSelection(List<Quote> evidence) {}
    public enum DecisionStance { MATCHES, CONTRADICTS, UNRELATED }
    public record ReportDecision(String evidenceId, DecisionStance stance, String reason) {}
    public record ReportReview(List<ReportDecision> comparisons) {}
    public record SourceSelection(List<Quote> evidence) {}
    public record Imvain(Criterion independent, Criterion multiple, Criterion verify, Criterion authoritative,
                         Criterion informed, Criterion named) {
        public Map<String, Criterion> asMap() {
            return Map.of("independent", independent, "multiple", multiple, "verify", verify,
                "authoritative", authoritative, "informed", informed, "named", named);
        }
    }
    public record SourceReview(String verificationNote, Imvain imvain, List<Quote> evidence) {}
    public record OverallSummary(String overallComment, String crossReportOverview,
                                 String sourceAssessmentOverview, List<String> unverifiedPoints) {}
}
