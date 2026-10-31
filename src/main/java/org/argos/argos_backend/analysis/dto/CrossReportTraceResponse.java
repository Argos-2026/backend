package org.argos.argos_backend.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import org.argos.argos_backend.analysis.domain.ReportingModels.*;
import org.argos.argos_backend.external.search.SearchClient.Provider;

@Schema(description = "메모리에 확보된 교차검증 Trace. 진행 중·실패 작업도 조회 가능하며 기사 전문·인증정보는 저장하지 않습니다.")
public record CrossReportTraceResponse(UUID analysisId, Status status, List<ClaimTrace> claims, Issue error) {
    public enum RequestStatus { STARTED, SUCCEEDED, FAILED }
    public enum QueryType { EXACT, EXPANDED, NEUTRAL, CHALLENGE }

    public record ClaimTrace(String claimId, String claimText, String exactQuery, String expandedQuery,
                             List<SearchTrace> searches, List<CandidateTrace> candidates,
                             List<String> selectedEvidenceIds, ReportVerdict verdict, String reason, Issue failure,
                             IssueFrame issue, String neutralQuery, String challengeQuery, List<Issue> enrichmentIssues,
                             TemporalContext temporalContext) {}

    @Schema(description = "논리 검색 요청 단위. resultCount는 SearchClient가 반환한 유효 URL 후보 수이며 실패·진행 중에는 null입니다. 내부 HTTP 재시도 횟수는 포함하지 않습니다.")
    public record SearchTrace(String requestId, Provider provider, QueryType queryType, String query,
                              RequestStatus status, Integer resultCount, Issue failure) {}

    @Schema(description = "검색 결과 출현별 후보. events는 실제 관찰한 단계만 포함합니다. llmSelected는 기존 select 응답만 뜻합니다. 관점 정책의 선정 결과는 verificationSelection을 사용하세요. claimAnalysis의 분류·reason 및 conflictResolution.check는 모델 평가이며, statement·quote·usedQuotes는 본문 대조 대상입니다.")
    public record CandidateTrace(String candidateId, String requestId, Provider provider, String url, String title,
                                 List<CandidateEvent> events, String evidenceId, Boolean llmSelected,
                                 ComparisonTrace comparison, DocumentMetadata document,
                                 CandidateClaim claimAnalysis, ConflictResolution conflictResolution,
                                 List<String> usedQuotes, String searchDescription, String searchDateHint,
                                 VerificationSelection verificationSelection, PrefetchPriority prefetchPriority,
                                 MatchResolution matchResolution) {}

    @Schema(description = "관점 분류 후 서버 정책으로 결정한 최종 비교 대상 여부. 기존 llmSelected는 기존 select 응답이 있을 때만 설정합니다.")
    public record VerificationSelection(boolean selected, String reason) {}

    @Schema(description = "수집한 원문의 메타데이터. 검색 제목·URL은 기존 후보 필드에 별도로 보존합니다. 전문은 포함하지 않습니다.")
    public record DocumentMetadata(String url, String title, String publisher, Instant publishedAt, boolean truncated) {}

    public record CandidateEvent(Stage stage, String code, String message) {}

    @Schema(description = "실제 비교 응답. UNRELATED도 보존하며 기존 결과의 비교 목록·집계에서는 제외됩니다.")
    public record ComparisonTrace(String evidenceId,
        @Schema(allowableValues = {"MATCHES", "CONTRADICTS", "UNRELATED"}) String stance, String reason) {}
}
