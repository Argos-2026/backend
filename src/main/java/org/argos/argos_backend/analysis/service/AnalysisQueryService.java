package org.argos.argos_backend.analysis.service;

import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import org.argos.argos_backend.analysis.dto.CrossReportResultResponse;
import org.argos.argos_backend.analysis.dto.SourceResultResponse;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse;
import org.argos.argos_backend.analysis.dto.AnalysisStatusResponse;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.stereotype.Service;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

@Service
public class AnalysisQueryService {
    private final AnalysisStore store;

    public AnalysisQueryService(AnalysisStore store) { this.store = store; }

    public AnalysisStatusResponse status(String analysisId) {
        Snapshot snapshot = find(analysisId);
        // Project one atomic snapshot. Never serialize article content or the stored result here.
        return new AnalysisStatusResponse(snapshot.analysisId(), snapshot.status(), snapshot.activeStages(),
            snapshot.expiresAt(), snapshot.warnings(), snapshot.error());
    }

    public Result result(String analysisId) {
        Snapshot snapshot = find(analysisId);
        return switch (snapshot.status()) {
            case QUEUED, RUNNING -> throw new ApiException(409, "ANALYSIS_NOT_READY", "분석이 진행 중입니다. 완료 후 결과를 조회해 주세요.");
            case FAILED -> throw new ApiException(409, "ANALYSIS_FAILED", "분석에 실패했습니다. 상태 조회에서 실패 이유를 확인해 주세요.");
            case COMPLETED, PARTIAL_COMPLETED -> {
                Result result = snapshot.result();
                if (result == null || !result.analysisId().equals(snapshot.analysisId()) || result.status() != snapshot.status())
                    throw new ApiException(500, "INTERNAL_SERVER_ERROR", "저장된 분석 결과를 조회하지 못했습니다.");
                yield result;
            }
        };
    }

    public Object result(String analysisId, String section) {
        if (!Set.of("all", "cross-report", "source").contains(section))
            throw new ApiException(400, "INVALID_SECTION", "section은 all, cross-report, source 중 하나여야 합니다.");
        Result result = result(analysisId);
        if (section.equals("all")) return result;
        Set<String> evidenceIds = new HashSet<>();
        if (section.equals("cross-report")) {
            result.crossReports().forEach(r -> r.comparisons().forEach(c -> evidenceIds.add(c.evidenceId())));
            return new CrossReportResultResponse(result.analysisId(), result.status(), result.article(),
                result.summary().crossReportOverview(), result.crossReports(),
                result.evidence().stream().filter(e -> evidenceIds.contains(e.evidenceId())).toList(), result.warnings());
        }
        result.sourceAssessments().forEach(a -> a.sources().forEach(s ->
            s.imvain().values().forEach(c -> evidenceIds.addAll(c.evidenceIds()))));
        return new SourceResultResponse(result.analysisId(), result.status(), result.article(),
            result.summary().sourceAssessmentOverview(), result.sourceAssessments(),
            result.evidence().stream().filter(e -> evidenceIds.contains(e.evidenceId())).toList(), result.warnings());
    }

    public CrossReportTraceResponse trace(String analysisId) {
        Snapshot snapshot = find(analysisId);
        return new CrossReportTraceResponse(snapshot.analysisId(), snapshot.status(), snapshot.traceClaims(), snapshot.error());
    }

    Snapshot find(String analysisId) {
        final UUID id;
        try {
            id = UUID.fromString(analysisId);
            // UUID.fromString also accepts abbreviated groups, which are not issued by this API.
            if (!id.toString().equalsIgnoreCase(analysisId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "INVALID_ANALYSIS_ID", "올바른 분석 ID를 입력해 주세요.");
        }
        return store.find(id).orElseThrow(() -> new ApiException(404, "ANALYSIS_NOT_FOUND",
            "분석 결과가 없거나 만료되었습니다. 기사 URL을 다시 제출해 주세요."));
    }
}
