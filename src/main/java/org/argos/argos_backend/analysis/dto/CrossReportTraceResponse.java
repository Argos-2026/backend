package org.argos.argos_backend.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import org.argos.argos_backend.external.search.SearchClient.Provider;

@Schema(description = "메모리에 확보된 교차검증 Trace. 진행 중·실패 작업도 조회 가능하며 기사 전문·인증정보는 저장하지 않습니다.")
public record CrossReportTraceResponse(UUID analysisId, Status status, List<ClaimTrace> claims, Issue error) {
    public enum RequestStatus { STARTED, SUCCEEDED, FAILED }
    public enum QueryType { EXACT, EXPANDED }

    public record ClaimTrace(String claimId, String claimText, String exactQuery, String expandedQuery,
                             List<SearchTrace> searches, List<CandidateTrace> candidates,
                             List<String> selectedEvidenceIds, ReportVerdict verdict, String reason, Issue failure) {}

    @Schema(description = "논리 검색 요청 단위. resultCount는 SearchClient가 반환한 유효 URL 후보 수이며 실패·진행 중에는 null입니다. 내부 HTTP 재시도 횟수는 포함하지 않습니다.")
    public record SearchTrace(String requestId, Provider provider, QueryType queryType, String query,
                              RequestStatus status, Integer resultCount, Issue failure) {}

    @Schema(description = "검색 결과 출현별 후보. events는 실제 관찰한 단계만 포함합니다. llmSelected=null은 선정 결과 없음, false는 LLM에 전달했으나 미선정입니다. 선정 이유는 기존 LLM 응답에 없어 제공하지 않습니다.")
    public record CandidateTrace(String candidateId, String requestId, Provider provider, String url, String title,
                                 List<CandidateEvent> events, String evidenceId, Boolean llmSelected,
                                 ComparisonTrace comparison) {}

    public record CandidateEvent(Stage stage, String code, String message) {}

    @Schema(description = "실제 비교 응답. UNRELATED도 보존하며 기존 결과의 비교 목록·집계에서는 제외됩니다.")
    public record ComparisonTrace(String evidenceId,
        @Schema(allowableValues = {"MATCHES", "CONTRADICTS", "UNRELATED"}) String stance, String reason) {}
}
