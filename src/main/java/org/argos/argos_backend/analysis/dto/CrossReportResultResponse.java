package org.argos.argos_backend.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

@Schema(description = "section=cross-report: 교차보도 결과와 해당 비교에서 참조한 근거만 반환합니다.")
public record CrossReportResultResponse(UUID analysisId, Status status, Article article, String crossReportOverview,
                                        List<CrossReport> crossReports, List<Evidence> evidence, List<Issue> warnings) {}
