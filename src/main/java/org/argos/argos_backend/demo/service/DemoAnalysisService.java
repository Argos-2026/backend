package org.argos.argos_backend.demo.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Stage;
import org.argos.argos_backend.analysis.domain.AnalysisModels.Status;
import org.argos.argos_backend.analysis.dto.AnalysisAcceptedResponse;
import org.argos.argos_backend.analysis.dto.AnalysisStatusResponse;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DemoAnalysisService {
    private static final String RESULT_RESOURCE = "demo/analysis-result.json";
    private static final Duration RUNNING_DURATION = Duration.ofSeconds(5);
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final Clock clock;
    private final JsonNode resultTemplate;
    private final ConcurrentMap<UUID, DemoAnalysis> analyses = new ConcurrentHashMap<>();

    public DemoAnalysisService(Clock clock, JsonMapper json) {
        this.clock = clock;
        this.resultTemplate = loadResultTemplate(json);
    }

    public AnalysisAcceptedResponse create() {
        Instant createdAt = clock.instant();
        DemoAnalysis analysis = new DemoAnalysis(UUID.randomUUID(), createdAt, createdAt.plus(RETENTION));
        analyses.put(analysis.id(), analysis);
        return new AnalysisAcceptedResponse(analysis.id(), Status.QUEUED, analysis.createdAt(), analysis.expiresAt());
    }

    public AnalysisStatusResponse status(String analysisId) {
        DemoAnalysis analysis = find(analysisId);
        Status status = statusOf(analysis);
        return new AnalysisStatusResponse(analysis.id(), status,
            status == Status.RUNNING ? java.util.List.of(Stage.RETRIEVING_EVIDENCE) : java.util.List.of(),
            analysis.expiresAt(), java.util.List.of(), null);
    }

    public JsonNode result(String analysisId) {
        DemoAnalysis analysis = find(analysisId);
        if (statusOf(analysis) == Status.RUNNING) {
            throw new ApiException(409, "ANALYSIS_NOT_READY", "분석이 진행 중입니다. 완료 후 결과를 조회해 주세요.");
        }
        ObjectNode result = (ObjectNode) resultTemplate.deepCopy();
        result.put("analysisId", analysis.id().toString());
        return result;
    }

    private Status statusOf(DemoAnalysis analysis) {
        return Duration.between(analysis.createdAt(), clock.instant()).compareTo(RUNNING_DURATION) < 0
            ? Status.RUNNING : Status.COMPLETED;
    }

    private DemoAnalysis find(String analysisId) {
        UUID id = parseId(analysisId);
        DemoAnalysis analysis = analyses.get(id);
        if (analysis == null || !clock.instant().isBefore(analysis.expiresAt())) {
            analyses.remove(id);
            throw new ApiException(404, "ANALYSIS_NOT_FOUND", "분석 결과가 없거나 만료되었습니다. 시연용 분석을 다시 요청해 주세요.");
        }
        return analysis;
    }

    private UUID parseId(String analysisId) {
        try {
            UUID id = UUID.fromString(analysisId);
            if (!id.toString().equalsIgnoreCase(analysisId)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "INVALID_ANALYSIS_ID", "올바른 분석 ID를 입력해 주세요.");
        }
    }

    private JsonNode loadResultTemplate(JsonMapper json) {
        try (InputStream input = new ClassPathResource(RESULT_RESOURCE).getInputStream()) {
            JsonNode template = json.readTree(input);
            if (!template.isObject()) throw new IllegalStateException("Demo result must be a JSON object");
            return template;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not load demo analysis result: " + RESULT_RESOURCE, e);
        }
    }

    private record DemoAnalysis(UUID id, Instant createdAt, Instant expiresAt) {}
}
