package org.argos.argos_backend.analysis.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AnalysisModels {
    private AnalysisModels() {}

    public enum Status { QUEUED, RUNNING, COMPLETED, PARTIAL_COMPLETED, FAILED }
    public enum Stage { FETCHING_ARTICLE, EXTRACTING_CLAIMS, RETRIEVING_EVIDENCE, SELECTING_EVIDENCE,
        VERIFYING_CLAIMS, ASSESSING_SOURCES, AGGREGATING_RESULT }
    public enum Verdict { SUPPORT, REFUTE, CONFLICT, INSUFFICIENT }
    public enum Stance { SUPPORTS, REFUTES, CONTEXT }
    public enum CriterionStatus { MET, NOT_MET, UNKNOWN }
    public enum SourceType { PERSON, ORGANIZATION, DOCUMENT, OTHER }
    public static final List<String> CRITERIA = List.of("independent", "multiple", "verify", "authoritative", "informed", "named");

    public record Issue(String code, Stage stage, String message) {}
    public record Article(String url, String title, String publisher, String author, Instant publishedAt) {}
    public record Summary(String overallComment, String factCheckOverview, String sourceAssessmentOverview, List<String> unverifiedPoints) {}
    public record EvidenceLink(String evidenceId, Stance stance) {}
    public record Claim(String claimId, String text, Verdict verdict, String reason, List<String> articleSourceIds,
                        List<EvidenceLink> evidenceLinks) {}
    public record Evidence(String evidenceId, String title, String publisher, String url, Instant publishedAt, String excerpt) {}
    public record Criterion(CriterionStatus status, String reason, List<String> evidenceIds) {}
    public record ArticleSource(String sourceId, String name, SourceType type, String citedInArticle,
                                String verificationNote, Map<String, Criterion> imvain) {}
    public record Result(UUID analysisId, Status status, String analysisOutcome, Article article, Summary summary,
                         List<Claim> claims, List<Evidence> evidence, List<ArticleSource> articleSources, List<Issue> warnings) {}
    public record Snapshot(UUID analysisId, String articleUrl, Status status, List<Stage> activeStages,
                           Instant createdAt, Instant expiresAt, List<Issue> warnings, Issue error, Result result) {}
}
