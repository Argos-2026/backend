package org.argos.argos_backend.external.llm;

import java.util.List;
import java.util.Map;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

public final class LlmModels {
    private LlmModels() {}
    public enum ClaimKind { FACT, ATTRIBUTION }
    public record DraftClaim(String text, String question, List<String> queries, ClaimKind kind, String speaker) {}
    public record DraftSource(String name, SourceType type, String citedInArticle, List<Integer> claimIndexes, boolean identifiable) {}
    public record Extraction(List<DraftClaim> claims, List<DraftSource> sources, boolean sourcesTruncated) {}
    public record Quote(String evidenceId, String quote) {}
    public record SelectedClaim(String claimId, List<Quote> evidence) {}
    public record Selection(List<SelectedClaim> claims) {}
    public record VerifiedClaim(String claimId, Verdict verdict, String reason, List<EvidenceLink> evidenceLinks,
                                boolean targetMatched, List<Quote> attributionQuotes) {}
    public record Verification(List<VerifiedClaim> claims) {}
    public record Imvain(Criterion independent, Criterion multiple, Criterion verify, Criterion authoritative, Criterion informed, Criterion named) {
        public Map<String, Criterion> asMap() {
            return Map.of("independent", independent, "multiple", multiple, "verify", verify,
                "authoritative", authoritative, "informed", informed, "named", named);
        }
    }
    public record SourceReview(String sourceId, String verificationNote, Imvain imvain, List<Quote> evidence) {}
    public record Assessment(List<SourceReview> sources) {}
    public record OverallSummary(String overallComment, String factCheckOverview, String sourceAssessmentOverview, List<String> unverifiedPoints) {}
}
