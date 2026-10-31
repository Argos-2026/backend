package org.argos.argos_backend.analysis.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Issue;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Stage;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Status;

public record AnalysisStatusResponse(UUID analysisId, Status status, List<Stage> activeStages, Instant expiresAt,
                                     List<Issue> warnings, Issue error) {}
