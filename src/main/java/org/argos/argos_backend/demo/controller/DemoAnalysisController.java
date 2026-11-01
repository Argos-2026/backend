package org.argos.argos_backend.demo.controller;

import org.argos.argos_backend.analysis.dto.AnalysisAcceptedResponse;
import org.argos.argos_backend.analysis.dto.AnalysisStatusResponse;
import org.argos.argos_backend.demo.service.DemoAnalysisService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/demo/analyses")
public class DemoAnalysisController implements DemoAnalysisApiDocs {
    private final DemoAnalysisService service;

    public DemoAnalysisController(DemoAnalysisService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<AnalysisAcceptedResponse> create(@RequestBody JsonNode body) {
        if (!body.isObject() || (body.has("articleUrl") && !body.get("articleUrl").isNull() && !body.get("articleUrl").isString())) {
            throw new org.argos.argos_backend.global.exception.ApiException(400, "INVALID_REQUEST", "요청 본문과 필드 타입을 확인해 주세요.");
        }
        String articleUrl = body.path("articleUrl").asString("").strip();
        if (articleUrl.isBlank()) {
            throw new org.argos.argos_backend.global.exception.ApiException(400, "INVALID_URL", "뉴스 기사 URL을 입력해 주세요.");
        }
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(service.create());
    }

    @GetMapping(value = "/{analysisId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<AnalysisStatusResponse> status(@PathVariable String analysisId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.status(analysisId));
    }

    @GetMapping(value = "/{analysisId}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    @Override
    public ResponseEntity<JsonNode> result(@PathVariable String analysisId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.result(analysisId));
    }
}
