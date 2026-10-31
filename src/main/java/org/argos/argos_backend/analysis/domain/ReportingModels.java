package org.argos.argos_backend.analysis.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Extracted text is grounded in a document; classifications and scope checks are model assessments. */
public final class ReportingModels {
    private ReportingModels() {}

    public record IssueFrame(String event, String subject, String target, String period, String metric,
                             String originalQuote, String announcementDate) {
        public IssueFrame(String event, String subject, String target, String period, String metric, String originalQuote) {
            this(event, subject, target, period, metric, originalQuote, "");
        }
    }
    @Schema(description = "발표 사건의 날짜와 통계 대상 기간을 분리합니다. 발표일 fallback은 사건 발생일의 확정이 아닙니다.")
    public record TemporalContext(Instant originalPublishedAt, String announcementDate, String announcementDateSource,
                                  List<String> targetPeriods, List<String> numericAnchors, List<String> eventAnchors,
                                  String action) {}
    @Schema(description = "전체 검색 후보를 수집 전에 평가한 순서·점수·수집 적격 여부·이유. 점수는 사실 판정이 아닙니다.")
    public record PrefetchPriority(int rank, int score, boolean eligible, List<String> reasons) {}
    public record MatchCheck(ScopeMatch sameEvent, ScopeMatch samePeriod, ScopeMatch sameValue,
                             ScopeMatch sameAction, ScopeMatch sameComparisonBasis,
                             String originalQuote, String externalQuote, String reason) {}
    public record MatchResolution(MatchCheck check, boolean accepted, String reason) {}
    @Schema(description = "관점 후보 분류이며 최종 일치·상반 판정이 아닙니다. 감성 차이는 사실 충돌이 아닙니다.")
    public enum Perspective { SUPPORT_CANDIDATE, CONFLICT_CANDIDATE, DIFFERENT_PERSPECTIVE, IRRELEVANT, INSUFFICIENT }
    public record CandidateClaim(String evidenceId, Perspective perspective, String statement, String quote,
                                 String reason, String duplicateOfEvidenceId, String sharedOriginQuote) {}
    public enum ScopeMatch { SAME, DIFFERENT, UNKNOWN, NOT_APPLICABLE }
    public record ConflictCheck(ScopeMatch sameEvent, ScopeMatch sameSubject, ScopeMatch sameTarget,
                                ScopeMatch samePeriod, ScopeMatch sameMetric, ScopeMatch sameUnit,
                                ScopeMatch sameDenominator, ScopeMatch sameAggregation, ScopeMatch sameConditions,
                                boolean mutuallyExclusive, String originalQuote, String externalQuote, String reason) {}
    @Schema(description = "check는 모델의 비교 조건 판단, accepted는 본문 인용·비교 조건 게이트 통과 여부입니다. accepted=true도 사실의 진위 확정이 아닙니다.")
    public record ConflictResolution(ConflictCheck check, boolean accepted, String reason) {}
}
