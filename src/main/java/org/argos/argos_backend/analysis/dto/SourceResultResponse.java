package org.argos.argos_backend.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

@Schema(description = "section=source: 정보원 평가와 해당 평가에서 참조한 근거만 반환합니다.")
public record SourceResultResponse(UUID analysisId, Status status, Article article, String sourceAssessmentOverview,
                                   List<SourceAssessment> sourceAssessments, List<Evidence> evidence, List<Issue> warnings) {}
