package org.argos.argos_backend.analysis.dto;

import java.time.Instant;
import java.util.UUID;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Status;

public record AnalysisAcceptedResponse(UUID analysisId, Status status, Instant createdAt, Instant expiresAt) {}
