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
import org.argos.argos_backend.analysis.dto.CrossReportResultResponse;
import org.argos.argos_backend.analysis.dto.SourceResultResponse;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse;
import org.argos.argos_backend.global.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "뉴스 분석", description = "분석 요청, 진행 상태 및 최종 결과 조회")
public interface AnalysisApiDocs {
    String ID_EXAMPLE = "a0f02db8-c8af-48db-b9fd-5d6f62303a11";

    @Operation(operationId = "createAnalysis", summary = "뉴스 분석 요청",
        description = "뉴스 URL을 접수하고 기사 수집·목적별 주장 추출·교차보도 비교·IMVAIN 평가를 비동기로 실행합니다. "
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
        description = "section 기본값 all은 기존 전체 응답을 유지합니다. section=cross-report는 CrossReportResultResponse, "
            + "section=source는 SourceResultResponse를 반환합니다. 각 부분 응답의 evidence는 해당 영역에서 참조한 근거만 포함하며 warnings와 status는 전체 작업 기준입니다. "
            + "Swagger 응답 예시와 oneOf 스키마에서 section별 DTO를 확인하세요. 완료·부분 완료된 분석의 기사 정보, 교차보도 결과와 주장별 IMVAIN 평가를 반환합니다. "
            + "두 분석은 같은 기사에서 목적별 주장을 각각 최대 3개씩 독립적으로 선정합니다. "
            + "crossReports의 판정은 MATCHED·CONTRADICTED·MIXED·UNCONFIRMED이며 다른 언론사 보도의 일치 여부만 뜻합니다. "
            + "같은 보도는 실제 사실의 확정이 아닙니다. 검색 실패는 analysisFailed와 warnings로 구분합니다. "
            + "sourceAssessments는 주장별 실제 인용 정보원을 각각 평가합니다. "
            + "IMVAIN은 independent·multiple·verify·authoritative·informed·named 6개 기준에 "
            + "MET·NOT_MET·UNKNOWN 상태와 이유·evidenceIds를 제공합니다. "
            + "개별 정보원 평가 실패 시 assessmentFailed=true이며 다른 정보원 결과는 유지됩니다. "
            + "crossReports와 sourceAssessments가 모두 비어 있으면 선정할 주장이 없었던 경우입니다. "
            + "비교 기사와 IMVAIN 근거 ID는 evidence의 원문 URL·발췌와 연결합니다. "
            + "보도 관점 후보와 충돌 비교 조건은 별도 cross-report/trace에서 조회할 수 있으며 후보 유형 자체는 최종 상반 판정이 아닙니다. "
            + "저장된 결과만 읽으며 검색·LLM을 다시 호출하지 않습니다. 기사 전문과 API 키는 포함하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "COMPLETED 또는 PARTIAL_COMPLETED 결과. 부분 완료 시 warnings를 함께 확인하세요.",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json",
                schema = @Schema(oneOf = {AnalysisModels.Result.class, CrossReportResultResponse.class, SourceResultResponse.class}),
                examples = {
                    @ExampleObject(name = "all (기본값)", value = AnalysisResponseExamples.ALL),
                    @ExampleObject(name = "cross-report", value = AnalysisResponseExamples.CROSS_REPORT),
                    @ExampleObject(name = "source", value = AnalysisResponseExamples.SOURCE)
                })),
        @ApiResponse(responseCode = "400", description = "INVALID_ANALYSIS_ID: UUID 형식 오류 / INVALID_SECTION: all·cross-report·source 이외의 section",
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
    ResponseEntity<?> result(
        @Parameter(name = "analysisId", in = ParameterIn.PATH, required = true, description = "완료·부분 완료된 분석 UUID",
            example = ID_EXAMPLE, schema = @Schema(type = "string", format = "uuid")) String analysisId,
        @Parameter(name = "section", in = ParameterIn.QUERY, description = "조회 영역. 생략 시 기존 전체 결과를 반환합니다.",
            schema = @Schema(type = "string", defaultValue = "all", allowableValues = {"all", "cross-report", "source"})) String section);

    @Operation(operationId = "getCrossReportTrace", summary = "교차검증 Trace 조회",
        description = "검색·본문 수집·Java 필터링·LLM 선정·비교의 실제 처리 기록을 조회합니다. "
            + "QUEUED·RUNNING·FAILED 상태도 HTTP 200으로 반환하며 아직 수행하지 않은 단계는 기록하지 않습니다. "
            + "검색 후보는 검색 결과 출현별로 구분하며 중복 근거는 같은 evidenceId로 연결합니다. "
            + "COLLECTION_LIMIT_REACHED는 기존 수집 한도로 미처리한 후보입니다. events가 비어 있으면 수집 처리 기록이 없습니다. "
            + "기존 select 경로의 llmSelected=null은 선정 응답 없음, false는 LLM 미선정입니다. 기존 선정 응답의 이유는 생성하지 않습니다. "
            + "쟁점 관점 경로는 issue·neutralQuery·challengeQuery, NEUTRAL·CHALLENGE 검색 유형을 추가하고 verificationSelection에 서버의 최종 비교 선정 여부와 이유를 기록합니다. 이 경로는 llmSelected를 설정하지 않습니다. "
            + "claimAnalysis는 본문에서 확인한 statement·quote와 모델의 perspective·reason을 구분합니다. document는 수집 메타데이터, searchDescription·searchDateHint는 검색 응답의 요약·날짜 힌트이며 확정 근거가 아닙니다. "
            + "conflictResolution은 모델의 비교 조건과 서버의 상반 인정·탈락을 구분하며 usedQuotes는 최종 집계에 사용한 본문 인용입니다. enrichmentIssues는 관점 분석의 부분 실패·기존 경로 복구 기록입니다. "
            + "temporalContext는 원문 발행일 기준 절대 연도, 발표 사건일(또는 발행일 fallback)과 통계 대상 기간을 분리합니다. prefetchPriority는 전체 후보의 수집 전 순서·점수·적격 여부·이유이며 사실 판정이 아닙니다. "
            + "PREFETCH_REJECTED는 메타데이터에서 다른 발표 사건이 식별된 후보입니다. 발행일만으로 제외하지 않으며 이후 정정·회고도 보존합니다. matchResolution은 유지·상향·하향 주장의 수치·행위·비교 기준을 검증해 일치 인정/보류한 결과입니다. "
            + "UNRELATED 비교도 Trace에 보존하지만 기존 결과 집계에는 포함하지 않습니다. "
            + "Trace는 최선 노력으로 기록하며 작업과 동일한 메모리·만료 정책을 따릅니다. 저장된 기록만 읽고 외부 API는 호출하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재까지 확보된 Trace. error는 작업 전체 실패, claims[].failure는 주장 분석 실패 원인입니다.",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CrossReportTraceResponse.class),
                examples = {
                    @ExampleObject(name = "검색부터 최종 비교까지", value = AnalysisResponseExamples.TRACE),
                    @ExampleObject(name = "관점 후보와 충돌 검증", value = AnalysisResponseExamples.TRACE_PERSPECTIVE)
                })),
        @ApiResponse(responseCode = "400", description = "INVALID_ANALYSIS_ID: UUID 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "ANALYSIS_NOT_FOUND: 없는 ID·만료·서버 재시작으로 유실",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR: Trace 조회 중 서버 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    ResponseEntity<CrossReportTraceResponse> trace(
        @Parameter(name = "analysisId", in = ParameterIn.PATH, required = true, description = "분석 UUID",
            example = ID_EXAMPLE, schema = @Schema(type = "string", format = "uuid")) String analysisId);

    @Schema(name = "AnalysisRequest", description = "뉴스 분석 요청 본문")
    record AnalysisRequest(
        @Schema(description = "분석할 HTTP/HTTPS 뉴스 기사 URL", format = "uri", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "https://news.example.com/articles/12345") String articleUrl) {}
}
