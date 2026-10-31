package org.argos.argos_backend.analysis.controller;

import org.argos.argos_backend.analysis.domain.AnalysisModels;
import org.argos.argos_backend.analysis.controller.docs.AnalysisApiDocs;
import org.argos.argos_backend.analysis.dto.AnalysisAcceptedResponse;
import org.argos.argos_backend.analysis.dto.AnalysisStatusResponse;
import org.argos.argos_backend.analysis.service.AnalysisQueryService;
import org.argos.argos_backend.analysis.service.AnalysisService;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/analyses")
public class AnalysisController implements AnalysisApiDocs {
    private final AnalysisService service;
    private final AnalysisQueryService queries;
    public AnalysisController(AnalysisService service, AnalysisQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @GetMapping(value = "/{analysisId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<AnalysisStatusResponse> status(@PathVariable String analysisId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(queries.status(analysisId));
    }

    @GetMapping(value = "/{analysisId}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<AnalysisModels.Result> result(@PathVariable String analysisId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(queries.result(analysisId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<AnalysisAcceptedResponse> create(@RequestBody JsonNode body) {
        if (!body.isObject() || (body.has("articleUrl") && !body.get("articleUrl").isNull() && !body.get("articleUrl").isString())) {
            throw new ApiException(400, "INVALID_REQUEST", "요청 본문과 필드 타입을 확인해 주세요.");
        }
        String url = body.path("articleUrl").asString("").strip();
        if (url.isBlank()) throw new ApiException(400, "INVALID_URL", "뉴스 기사 URL을 입력해 주세요.");
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(service.submit(url));
    }
}
