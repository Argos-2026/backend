package org.argos.argos_backend.analysis.controller.docs;

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

@Tag(name = "뉴스 분석", description = "분석 요청, 진행 상태 및 최종 결과 조회")
public interface AnalysisApiDocs {
    String ID_EXAMPLE = "a0f02db8-c8af-48db-b9fd-5d6f62303a11";

    @Operation(operationId = "createAnalysis", summary = "뉴스 분석 요청",
        description = "뉴스 URL을 접수하고 기사 수집·주장 추출·외부 근거 검색·사실 검증·IMVAIN 평가를 비동기로 실행합니다. "
            + "202는 접수 성공이며 분석 완료를 의미하지 않습니다. 반환된 analysisId로 상태를 조회하세요. "
            + "필수 외부 API 키가 미설정이면 작업을 등록하지 않고 503을 반환합니다.",
        requestBody = @RequestBody(required = true, description = "분석할 뉴스 기사 URL",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisRequest.class),
                examples = @ExampleObject(name = "뉴스 URL", value = "{\"articleUrl\":\"https://news.example.com/articles/12345\"}"))))
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "분석 작업 접수 성공. status는 항상 QUEUED입니다.",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisAcceptedResponse.class),
                examples = @ExampleObject(value = """
                    {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"QUEUED",
                    "createdAt":"2026-10-31T08:00:00Z","expiresAt":"2026-11-01T08:00:00Z"}
                    """))),
        @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 본문·JSON·타입 오류 / INVALID_URL: URL 누락·형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = "{\"error\":{\"code\":\"INVALID_URL\",\"message\":\"올바른 뉴스 기사 URL을 입력해 주세요.\"}}"))),
        @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE: application/json이 아닌 요청",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "UNSUPPORTED_URL: 내부망 주소·지원하지 않는 프로토콜·포트 등 허용되지 않는 URL",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED: 작업 실행·대기·보관 한도 초과",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR: 요청 처리 중 서버 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE: 필수 외부 API 키 미설정 또는 작업 접수 불가",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<AnalysisAcceptedResponse> create(JsonNode body);

    @Operation(operationId = "getAnalysisStatus", summary = "분석 상태 조회",
        description = "QUEUED·RUNNING일 때만 2초 간격으로 재조회합니다. "
            + "COMPLETED·PARTIAL_COMPLETED이면 결과를 조회하고, FAILED이면 error를 확인하세요. "
            + "분석 자체가 실패해도 상태 조회 성공은 HTTP 200입니다. "
            + "대기·종료 상태의 activeStages는 빈 배열이며 error는 FAILED에서만 객체입니다. "
            + "외부 API를 다시 호출하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "상태 조회 성공. 부분 실패는 warnings, 전체 실패는 error에 표시합니다.",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisStatusResponse.class),
                examples = {
                    @ExampleObject(name = "분석 중", value = """
                        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"RUNNING",
                        "activeStages":["RETRIEVING_EVIDENCE"],"expiresAt":"2026-11-01T08:00:00Z","warnings":[],"error":null}
                        """),
                    @ExampleObject(name = "분석 실패", value = """
                        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"FAILED",
                        "activeStages":[],"expiresAt":"2026-11-01T08:00:00Z","warnings":[],
                        "error":{"code":"ARTICLE_FETCH_FAILED","stage":"FETCHING_ARTICLE","message":"기사 원문을 가져오지 못했습니다."}}
                        """)
                })),
        @ApiResponse(responseCode = "400", description = "INVALID_ANALYSIS_ID: 표준 UUID 형식이 아닌 분석 ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "ANALYSIS_NOT_FOUND: 없는 ID·24시간 만료·서버 재시작으로 유실",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = "{\"error\":{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"분석 결과가 없거나 만료되었습니다. 기사 URL을 다시 제출해 주세요.\"}}"))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR: 상태 조회 중 서버 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<AnalysisStatusResponse> status(
        @Parameter(name = "analysisId", in = ParameterIn.PATH, required = true, description = "접수 시 발급된 분석 UUID",
            example = ID_EXAMPLE, schema = @Schema(type = "string", format = "uuid")) String analysisId);

    @Operation(operationId = "getAnalysisResult", summary = "분석 결과 조회",
        description = "완료·부분 완료된 분석의 기사 정보, 종합 분석, 주장별 판정·근거, 내부 정보원 평가를 반환합니다. "
            + "주장은 최대 3개이며 판정은 SUPPORT·REFUTE·CONFLICT·INSUFFICIENT 중 하나입니다. "
            + "발언 여부와 발언 내용의 사실 여부는 구분합니다. 발언 주장에는 발언자를 명시한 원문 근거가 필요하며, 내용 관련 자료만 있으면 INSUFFICIENT로 처리합니다. "
            + "검증 가능한 주장이 없으면 analysisOutcome=NO_VERIFIABLE_CLAIMS와 claims=[]를 반환합니다. "
            + "기사 발행 출처와 기사 내부 정보원·외부 근거를 구분합니다. "
            + "IMVAIN은 independent·multiple·verify·authoritative·informed·named 6개 기준에 "
            + "MET·NOT_MET·UNKNOWN 상태와 이유·evidenceIds를 제공합니다. "
            + "정보원 평가 실패 시 articleSources와 summary.sourceAssessmentOverview는 null, "
            + "평가 성공 후 정보원이 없으면 articleSources=[]입니다. "
            + "articleSources의 정보원 ID와 evidence의 근거 ID로 상세 자료를 연결합니다. "
            + "저장된 결과만 읽으며 검색·LLM을 다시 호출하지 않습니다. 기사 전문과 API 키는 포함하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "COMPLETED 또는 PARTIAL_COMPLETED 결과. 부분 완료 시 warnings를 함께 확인하세요.",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AnalysisModels.Result.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_ANALYSIS_ID: 표준 UUID 형식이 아닌 분석 ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "ANALYSIS_NOT_FOUND: 없는 ID·24시간 만료·서버 재시작으로 유실",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "ANALYSIS_NOT_READY: 대기·분석 중 / ANALYSIS_FAILED: 분석 전체 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class), examples = {
                @ExampleObject(name = "분석 중", value = "{\"error\":{\"code\":\"ANALYSIS_NOT_READY\",\"message\":\"분석이 진행 중입니다. 완료 후 결과를 조회해 주세요.\"}}"),
                @ExampleObject(name = "분석 실패", value = "{\"error\":{\"code\":\"ANALYSIS_FAILED\",\"message\":\"분석에 실패했습니다. 상태 조회에서 실패 이유를 확인해 주세요.\"}}")
            })),
        @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR: 결과 조회 중 서버 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<AnalysisModels.Result> result(
        @Parameter(name = "analysisId", in = ParameterIn.PATH, required = true, description = "완료·부분 완료된 분석 UUID",
            example = ID_EXAMPLE, schema = @Schema(type = "string", format = "uuid")) String analysisId);

    @Schema(name = "AnalysisRequest", description = "뉴스 분석 요청 본문")
    record AnalysisRequest(
        @Schema(description = "분석할 HTTP/HTTPS 뉴스 기사 URL", format = "uri", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "https://news.example.com/articles/12345") String articleUrl) {}
}
