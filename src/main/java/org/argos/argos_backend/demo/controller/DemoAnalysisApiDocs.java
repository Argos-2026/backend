package org.argos.argos_backend.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.argos.argos_backend.analysis.domain.AnalysisModels;
import org.argos.argos_backend.analysis.dto.AnalysisAcceptedResponse;
import org.argos.argos_backend.analysis.dto.AnalysisStatusResponse;
import org.argos.argos_backend.global.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "시연용 뉴스 분석", description = "외부 API 호출 없이 5초간 RUNNING 상태를 보여주는 더미 분석 API")
public interface DemoAnalysisApiDocs {
    String ID_EXAMPLE = "a0f02db8-c8af-48db-b9fd-5d6f62303a11";

    @Operation(operationId = "createDemoAnalysis", summary = "시연용 뉴스 분석 요청",
        description = "요청을 접수하고 5초 동안 RUNNING 상태를 유지한 뒤 정적 결과 JSON을 반환합니다. 외부 API 키나 실제 기사 URL 접근은 필요하지 않습니다.",
        requestBody = @RequestBody(required = true, description = "시연할 뉴스 기사 URL",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DemoAnalysisRequest.class),
                examples = @ExampleObject(value = "{\"articleUrl\":\"https://news.example.com/articles/demo\"}"))))
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "시연용 분석 작업 접수 성공",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisAcceptedResponse.class),
                examples = @ExampleObject(value = "{\"analysisId\":\"a0f02db8-c8af-48db-b9fd-5d6f62303a11\",\"status\":\"QUEUED\",\"createdAt\":\"2026-10-31T08:00:00Z\",\"expiresAt\":\"2026-10-31T08:10:00Z\"}"))),
        @ApiResponse(responseCode = "400", description = "INVALID_REQUEST 또는 INVALID_URL",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<AnalysisAcceptedResponse> create(JsonNode body);

    @Operation(operationId = "getDemoAnalysisStatus", summary = "시연용 분석 상태 조회",
        description = "접수 후 5초 동안 RUNNING을 반환합니다. 이후 COMPLETED가 되면 결과 조회 API를 호출할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 조회 성공",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisStatusResponse.class),
                examples = @ExampleObject(value = "{\"analysisId\":\"a0f02db8-c8af-48db-b9fd-5d6f62303a11\",\"status\":\"RUNNING\",\"activeStages\":[\"RETRIEVING_EVIDENCE\"],\"expiresAt\":\"2026-10-31T08:10:00Z\",\"warnings\":[],\"error\":null}"))),
        @ApiResponse(responseCode = "400", description = "INVALID_ANALYSIS_ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "ANALYSIS_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<AnalysisStatusResponse> status(
        @Parameter(name = "analysisId", in = ParameterIn.PATH, required = true, description = "접수 시 발급된 분석 UUID",
            example = ID_EXAMPLE, schema = @Schema(type = "string", format = "uuid")) String analysisId);

    @Operation(operationId = "getDemoAnalysisResult", summary = "시연용 분석 결과 조회",
        description = "5초가 지나면 실제 분석 결과 API와 동일한 전체 응답 구조를 정적 JSON에서 읽어 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "시연용 분석 결과",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisModels.Result.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_ANALYSIS_ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "ANALYSIS_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "ANALYSIS_NOT_READY: 5초 대기 중",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<JsonNode> result(
        @Parameter(name = "analysisId", in = ParameterIn.PATH, required = true, description = "접수 시 발급된 분석 UUID",
            example = ID_EXAMPLE, schema = @Schema(type = "string", format = "uuid")) String analysisId);

    @Schema(name = "DemoAnalysisRequest", description = "시연용 분석 요청")
    record DemoAnalysisRequest(
        @Schema(description = "시연 화면에 표시할 기사 URL", example = "https://news.example.com/articles/demo", requiredMode = Schema.RequiredMode.REQUIRED)
        String articleUrl) {}
}
