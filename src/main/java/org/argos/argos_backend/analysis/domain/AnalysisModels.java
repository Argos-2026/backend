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
    public enum ReportVerdict { MATCHED, CONTRADICTED, MIXED, UNCONFIRMED }
    public enum ReportStance { MATCHES, CONTRADICTS }
    public enum CriterionStatus { MET, NOT_MET, UNKNOWN }
    public enum SourceType { PERSON, ORGANIZATION, DOCUMENT, OTHER }
    public static final List<String> CRITERIA = List.of("independent", "multiple", "verify", "authoritative", "informed", "named");

    public record Issue(String code, Stage stage, String message) {}
    public record Article(String url, String title, String publisher, String author, Instant publishedAt) {}
    public record Summary(String overallComment, String crossReportOverview, String sourceAssessmentOverview,
                          List<String> unverifiedPoints) {}
    public record ReportComparison(String evidenceId, ReportStance stance, String reason) {}
    public record CrossReport(String claimId, String text, ReportVerdict verdict, String reason,
                              int matchingCount, int contradictingCount, List<ReportComparison> comparisons,
                              boolean analysisFailed) {}
    public record Evidence(String evidenceId, String title, String publisher, String url, Instant publishedAt, String excerpt) {}
    public record Criterion(CriterionStatus status, String reason, List<String> evidenceIds) {}
    public record ReviewedSource(String sourceId, String name, String affiliation, SourceType type,
                                 String citedInArticle, String verificationNote, Map<String, Criterion> imvain,
                                 boolean assessmentFailed) {}
    public record SourceAssessment(String claimId, String text, List<ReviewedSource> sources) {}
    public record Result(UUID analysisId, Status status, Article article, Summary summary,
                         List<CrossReport> crossReports, List<SourceAssessment> sourceAssessments,
                         List<Evidence> evidence, List<Issue> warnings) {}
    public record Snapshot(UUID analysisId, String articleUrl, Status status, List<Stage> activeStages,
                           Instant createdAt, Instant expiresAt, List<Issue> warnings, Issue error, Result result,
                           List<org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.ClaimTrace> traceClaims) {
        public Snapshot(UUID analysisId, String articleUrl, Status status, List<Stage> activeStages,
                        Instant createdAt, Instant expiresAt, List<Issue> warnings, Issue error, Result result) {
            this(analysisId, articleUrl, status, activeStages, createdAt, expiresAt, warnings, error, result, List.of());
        }
    }
}
