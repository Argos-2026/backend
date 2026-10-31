# ARGOS 교차검증 Trace 및 결과 조회 API 변경 보고

기존 검색·본문 수집·Java 필터링·LLM 선정·비교·집계 기준을 유지하면서, 작업의 메모리에 교차검증 Trace를 추가했다. 기존 `Result`, `CrossReport`, `SourceAssessment`, `Evidence`, `Issue`를 재사용한다. DB나 별도 저장소는 추가하지 않았다.

## API 및 응답 DTO

| GET 경로 | 응답 DTO | 동작 |
| --- | --- | --- |
| `/api/v1/analyses/{id}/result` | 기존 `AnalysisModels.Result` | 기존 전체 응답 유지 |
| `/api/v1/analyses/{id}/result?section=all` | 기존 `AnalysisModels.Result` | 기본값과 동일 |
| `/api/v1/analyses/{id}/result?section=cross-report` | `CrossReportResultResponse` | 기사·교차보도 요약·주장별 비교·해당 비교의 근거 |
| `/api/v1/analyses/{id}/result?section=source` | `SourceResultResponse` | 기사·정보원 평가 요약·주장별 평가·해당 평가의 근거 |
| `/api/v1/analyses/{id}/cross-report/trace` | `CrossReportTraceResponse` | 확보된 주장별 검색·후보 처리·선정·비교·실패 이력 |

모든 조회는 `Cache-Control: no-store`이며 외부 검색·LLM을 다시 호출하지 않는다. 부분 결과의 `status`와 `warnings`는 전체 작업 기준이다. 부분 결과의 `evidence`는 해당 영역이 참조한 ID로만 필터링하며 기존 순서를 유지한다.

기존 결과 조회의 미완료·전체 실패 `409`, 잘못된 ID `400`, 없는 ID·만료 `404` 처리를 유지한다. 지원하지 않는 section은 `400 INVALID_SECTION`이다. Trace는 QUEUED·RUNNING·COMPLETED·PARTIAL_COMPLETED·FAILED 상태 모두 `200`으로 조회할 수 있다. 대기·주장 추출 전에는 `claims=[]`이며, 전체 실패 이유는 최상위 `error`, 주장별 실패 이유는 `claims[].failure`에 담는다.

## Trace 해석 및 저장 정책

- `claimId`, `claimText`, `exactQuery`, `expandedQuery`는 실제 추출 결과를 그대로 보존한다. `searches[].query`는 SearchClient에 넘긴 문자열이다.
- 검색은 기존대로 EXACT/EXPANDED 각각 NAVER/BRAVE에 요청한다. `requestId`는 주장 안에서 식별한다. `STARTED`·`SUCCEEDED`·`FAILED` 상태와 안전한 기존 예외의 code/message/stage를 저장한다.
- `resultCount`는 SearchClient가 반환한 URL 있는 후보 수다. 각 요청은 기존대로 최대 5개이며, 원격 응답의 전체 검색 건수나 URL 없는 항목 수가 아니다. 실패·진행 중에는 null이다. 내부 HTTP 재시도는 하나의 논리 요청으로 표시한다.
- `candidateId`는 검색 결과 출현별 ID다. 같은 URL도 검색별로 남고 `requestId`, provider, URL, 검색 제목으로 출처를 연결한다. 검색 반환 순서를 요청별로 보존한다.
- `events`에는 관찰한 단계만 담는다. 본문 수집이나 LLM 처리를 하지 않은 후보에 성공·실패·미선정 결과를 만들어 넣지 않는다. `llmSelected=null`은 선정 결과 없음, false는 LLM에 제공한 후보의 미선정, true는 선정이다.
- LLM 선정 응답은 근거 ID와 인용문만 제공하므로 **선정·미선정 이유는 생성하지 않는다**. 선정 이벤트의 message는 null이다. 비교 단계에서는 실제 stance/reason을 저장한다.
- `selectedEvidenceIds`는 LLM이 실제 선정한 근거다. 비교에서 `UNRELATED`가 되더라도 Trace에 남는다. 기존 결과의 comparisons/evidence 및 집계에는 기존처럼 포함되지 않으므로 Trace의 모든 ID가 결과 evidence에 존재하는 것은 아니다.
- 원문과 본문이 같은 후보, 같은 호스트·언론사의 후보 등 기존 Java 제외 분기를 기록한다. 후보끼리 URL/본문이 중복되어 기존 EvidencePool이 근거를 재사용하면 같은 evidenceId로 연결한다.
- 외부 기사 전문, 검색/LLM 요청 헤더, API 인증정보, 임의의 원격 응답이나 알 수 없는 예외 메시지는 저장하지 않는다. 예상치 못한 예외는 종류와 일반적인 안전 메시지만 남긴다.
- AnalysisContext에서 기록과 불변 스냅샷 생성을 동기화하고, 기존 AnalysisJob에 스냅샷을 저장한다. 기록·저장 callback의 RuntimeException은 분석 결과·warnings·제어 흐름에 전파하지 않는다.
- 완료/실패한 작업의 스냅샷은 고정된다. 타이머가 작업을 실패 처리한 뒤 늦게 끝난 외부 요청은 기록을 덮어쓰지 않는다. Trace는 확보한 시점까지의 기록이며 최상위 timeout error와 함께 읽어야 한다.
- 작업과 동일하게 기본 24시간 메모리에 보관하며, 만료·서버 재시작 시 유실된다. 별도 재처리·영속화 정책은 추가하지 않았다.

| events.code | 의미 |
| --- | --- |
| `FETCH_STARTED`, `FETCH_SUCCEEDED` | 본문 수집 호출 시작·성공 |
| `DOCUMENT_CACHE_HIT` | 해당 작업에서 이미 수집한 본문 재사용 |
| `ARTICLE_FETCH_FAILED` 등 기존 AnalysisFailure.code | 수집 실패·중단의 기존 안전 메시지 |
| `URL_REJECTED`, `FETCH_URL_REJECTED` | 최초 URL 또는 수집 중 리다이렉트 URL 거부 |
| `ORIGINAL_ARTICLE_URL` | 분석 대상 기사 URL |
| `DUPLICATE_URL` | 동일 주장 수집 과정에서 방문한 URL |
| `SAME_CONTENT_AS_ORIGINAL` | 원본 기사와 같은 본문 |
| `SAME_HOST_AS_ORIGINAL` | 원본 기사와 같은 호스트 |
| `SAME_PUBLISHER_AS_ORIGINAL` | 원본 기사와 같은 발행 언론사 |
| `CANDIDATE_ACCEPTED` | 신규 근거로 수집 후보에 포함 |
| `EVIDENCE_REUSED` | 중복 URL/본문의 기존 근거 ID 재사용 |
| `COLLECTION_LIMIT_REACHED` | 기존 수집 시도/문서 수 한도로 미처리 |
| `LLM_SELECTED`, `LLM_NOT_SELECTED` | 유효한 선정 응답에서 선정·미선정 |
| `COMPARED` | 유효한 비교 응답 확보 |
| `CANDIDATE_PROCESSING_FAILED` | 예상치 못한 후보 처리 오류, 기존처럼 상위로 전파 |

본문 수집 전에 중단되면 후보의 events가 비어 있을 수 있다. 수집 시작 후 중단되면 확보한 이벤트까지만 남는다. 주장별 failure와 작업 error를 함께 확인한다.

## API별 응답 JSON 예시

아래는 Swagger에도 등록된 설명용 예시다. all은 선정된 주장이 없는 경우이며, 나머지는 각 DTO를 보여주는 독립 예시다.

### GET /api/v1/analyses/{id}/result 또는 ?section=all

```json
{
  "analysisId": "a0f02db8-c8af-48db-b9fd-5d6f62303a11",
  "status": "COMPLETED",
  "article": {
    "url": "https://news.example.com/articles/12345",
    "title": "기사 제목",
    "publisher": "예시 언론사",
    "author": null,
    "publishedAt": null
  },
  "summary": {
    "overallComment": "분석할 주장을 찾지 못했습니다.",
    "crossReportOverview": "비교 대상 없음",
    "sourceAssessmentOverview": "정보원 평가 대상 없음",
    "unverifiedPoints": []
  },
  "crossReports": [],
  "sourceAssessments": [],
  "evidence": [],
  "warnings": []
}
```

### GET /api/v1/analyses/{id}/result?section=cross-report

```json
{
  "analysisId": "a0f02db8-c8af-48db-b9fd-5d6f62303a11",
  "status": "COMPLETED",
  "article": {
    "url": "https://news.example.com/articles/12345",
    "title": "기사 제목",
    "publisher": "예시 언론사",
    "author": null,
    "publishedAt": null
  },
  "crossReportOverview": "다른 언론사에서 일치하는 보도를 확인했습니다.",
  "crossReports": [
    {
      "claimId": "report-1",
      "text": "복지 예산이 조정된다.",
      "verdict": "MATCHED",
      "reason": "일치 보도 1건을 확인했습니다.",
      "matchingCount": 1,
      "contradictingCount": 0,
      "comparisons": [
        {
          "evidenceId": "evidence-1",
          "stance": "MATCHES",
          "reason": "같은 예산 조정을 보도합니다."
        }
      ],
      "analysisFailed": false
    }
  ],
  "evidence": [
    {
      "evidenceId": "evidence-1",
      "title": "예산 조정 보도",
      "publisher": "다른일보",
      "url": "https://other.example.com/news/1",
      "publishedAt": null,
      "excerpt": "복지 예산을 조정한다."
    }
  ],
  "warnings": []
}
```

### GET /api/v1/analyses/{id}/result?section=source

```json
{
  "analysisId": "a0f02db8-c8af-48db-b9fd-5d6f62303a11",
  "status": "COMPLETED",
  "article": {
    "url": "https://news.example.com/articles/12345",
    "title": "기사 제목",
    "publisher": "예시 언론사",
    "author": null,
    "publishedAt": null
  },
  "sourceAssessmentOverview": "기관의 인용 내용을 외부 자료와 비교했습니다.",
  "sourceAssessments": [
    {
      "claimId": "source-claim-1",
      "text": "기관은 복지 예산 조정을 발표했다.",
      "sources": [
        {
          "sourceId": "source-1-1",
          "name": "기관",
          "affiliation": "",
          "type": "ORGANIZATION",
          "citedInArticle": "기관은 예산을 조정한다고 밝혔다.",
          "verificationNote": "기관 발표 자료에서 인용 내용을 확인했습니다.",
          "imvain": {
            "independent": {
              "status": "UNKNOWN",
              "reason": "독립성을 확인할 자료가 없습니다.",
              "evidenceIds": []
            },
            "multiple": {
              "status": "NOT_MET",
              "reason": "해당 주장에 인용된 정보원은 하나입니다.",
              "evidenceIds": []
            },
            "verify": {
              "status": "MET",
              "reason": "발표 자료에서 확인했습니다.",
              "evidenceIds": [
                "evidence-2"
              ]
            },
            "authoritative": {
              "status": "UNKNOWN",
              "reason": "권한을 확인하지 못했습니다.",
              "evidenceIds": []
            },
            "informed": {
              "status": "UNKNOWN",
              "reason": "직접 취재 여부를 확인하지 못했습니다.",
              "evidenceIds": []
            },
            "named": {
              "status": "MET",
              "reason": "기사에 기관명이 명시되어 있습니다.",
              "evidenceIds": []
            }
          },
          "assessmentFailed": false
        }
      ]
    }
  ],
  "evidence": [
    {
      "evidenceId": "evidence-2",
      "title": "기관 발표 자료",
      "publisher": "기관",
      "url": "https://docs.example.com/budget",
      "publishedAt": null,
      "excerpt": "복지 예산을 조정한다."
    }
  ],
  "warnings": []
}
```

### GET /api/v1/analyses/{id}/cross-report/trace

```json
{
  "analysisId": "a0f02db8-c8af-48db-b9fd-5d6f62303a11",
  "status": "COMPLETED",
  "claims": [
    {
      "claimId": "report-1",
      "claimText": "복지 예산이 조정된다.",
      "exactQuery": "복지 예산 조정",
      "expandedQuery": "복지 사업 변경",
      "searches": [
        {
          "requestId": "search-1",
          "provider": "NAVER",
          "queryType": "EXACT",
          "query": "복지 예산 조정",
          "status": "SUCCEEDED",
          "resultCount": 1,
          "failure": null
        },
        {
          "requestId": "search-2",
          "provider": "BRAVE",
          "queryType": "EXACT",
          "query": "복지 예산 조정",
          "status": "SUCCEEDED",
          "resultCount": 0,
          "failure": null
        },
        {
          "requestId": "search-3",
          "provider": "NAVER",
          "queryType": "EXPANDED",
          "query": "복지 사업 변경",
          "status": "SUCCEEDED",
          "resultCount": 0,
          "failure": null
        },
        {
          "requestId": "search-4",
          "provider": "BRAVE",
          "queryType": "EXPANDED",
          "query": "복지 사업 변경",
          "status": "SUCCEEDED",
          "resultCount": 0,
          "failure": null
        }
      ],
      "candidates": [
        {
          "candidateId": "search-1-candidate-1",
          "requestId": "search-1",
          "provider": "NAVER",
          "url": "https://other.example.com/news/1",
          "title": "예산 조정 보도",
          "events": [
            {
              "stage": "RETRIEVING_EVIDENCE",
              "code": "FETCH_STARTED",
              "message": null
            },
            {
              "stage": "RETRIEVING_EVIDENCE",
              "code": "FETCH_SUCCEEDED",
              "message": null
            },
            {
              "stage": "RETRIEVING_EVIDENCE",
              "code": "CANDIDATE_ACCEPTED",
              "message": null
            },
            {
              "stage": "SELECTING_EVIDENCE",
              "code": "LLM_SELECTED",
              "message": null
            },
            {
              "stage": "VERIFYING_CLAIMS",
              "code": "COMPARED",
              "message": null
            }
          ],
          "evidenceId": "evidence-1",
          "llmSelected": true,
          "comparison": {
            "evidenceId": "evidence-1",
            "stance": "MATCHES",
            "reason": "같은 예산 조정을 보도합니다."
          }
        }
      ],
      "selectedEvidenceIds": [
        "evidence-1"
      ],
      "verdict": "MATCHED",
      "reason": "일치 보도 1건을 확인했습니다.",
      "failure": null
    }
  ],
  "error": null
}
```

## Swagger 확인 방법

1. 기존 실행 방법으로 서버를 실행한다. 예: `./gradlew bootRun`.
2. `http://localhost:8080/swagger-ui.html`에서 **분석 결과 조회**를 연다.
3. `section`의 기본값 all과 허용값 all/cross-report/source를 확인한다.
4. 200 응답의 oneOf에서 `Result`, `CrossReportResultResponse`, `SourceResultResponse`를 확인한다. Examples의 `all (기본값)`, `cross-report`, `source`를 선택하면 각 JSON 예시가 표시된다.
5. **교차검증 Trace 조회**에서 `CrossReportTraceResponse` 및 중첩 ClaimTrace/SearchTrace/CandidateTrace/CandidateEvent/ComparisonTrace를 확인한다.
6. 저장된 분석 ID로 Try it out을 실행한다. 완료 전에는 결과 API가 409, Trace API가 200을 반환한다. 실패한 ID도 Trace 조회가 가능하다.
7. 원문 명세는 `http://localhost:8080/v3/api-docs`에서 확인한다. 동일한 HTTP 메서드와 경로의 section 변형은 OpenAPI의 단일 operation 안에서 oneOf 및 예시로 구분한다.

Swagger UI·명세 조회에는 API 키가 필요하지 않다. 신규 분석 요청은 기존처럼 키가 필요하다. 테스트에서는 로컬 HTTP 서버와 가짜 작업 및 모의 외부 응답을 사용해 키 없이 명세·응답을 검증했다.

## 변경 파일 및 DTO

기본 경로는 `src/main/java/org/argos/argos_backend/`이다.

| 파일 | 변경 |
| --- | --- |
| `analysis/domain/AnalysisContext.java` | Trace 기록 경계, 스냅샷 callback, 기존 생성자 유지 |
| `analysis/domain/AnalysisJob.java` | 실행 중 Trace 스냅샷 저장, 종료 후 불변성 |
| `analysis/domain/AnalysisModels.java` | 내부 Snapshot에 traceClaims 추가, 기존 생성자 유지. 공개 Result는 변경 없음 |
| `analysis/domain/CrossReportTrace.java` (추가) | 주장별 검색·후보·선정·비교 이력 수집 및 불변 스냅샷 |
| `analysis/service/AnalysisPipeline.java` | 기존 실행 분기별 Trace 관찰, 검색 결과의 요청별 식별 정보 연결 |
| `analysis/service/AnalysisService.java` | 기존 작업으로 Trace 저장 callback 연결 |
| `analysis/service/AnalysisQueryService.java` | section별 결과 투영, 해당 영역 근거 필터링, Trace 조회 |
| `analysis/controller/AnalysisController.java` | section 파라미터 및 Trace GET 매핑 |
| `analysis/controller/docs/AnalysisApiDocs.java` | oneOf, section 기본/허용값, 각 응답 DTO·오류·Trace 설명 |
| `analysis/controller/docs/AnalysisResponseExamples.java` (추가) | Swagger용 네 응답 예시 |
| `analysis/dto/CrossReportResultResponse.java` (추가) | 교차보도 전용 응답. 기존 Article/CrossReport/Evidence/Issue 재사용 |
| `analysis/dto/SourceResultResponse.java` (추가) | 정보원 전용 응답. 기존 Article/SourceAssessment/Evidence/Issue 재사용 |
| `analysis/dto/CrossReportTraceResponse.java` (추가) | ClaimTrace, SearchTrace, CandidateTrace, CandidateEvent, ComparisonTrace 및 검색 상태 enum |

테스트 기본 경로는 `src/test/java/org/argos/argos_backend/`이다.

| 파일 | 검증 |
| --- | --- |
| `analysis/service/AnalysisPipelineTraceTests.java` (추가) | 20개 중 10개 수집, 실제 쿼리, Java 제외 분기, 중복 근거, 수집/검색/LLM 실패, 미선정, 기존 판정·집계, 비밀정보 제외, callback 실패 격리, 진행 중 검색 및 스냅샷 불변성 |
| `analysis/controller/AnalysisApiIntegrationTests.java` | 실제 비동기 파이프라인에서 Trace 저장·HTTP 조회, 전체 검색 실패 보존, 재호출 및 본문/인증정보 노출 없음 |
| `analysis/controller/AnalysisResultApiIntegrationTests.java` | 기본 응답=all, 영역별 DTO 및 근거 분리, 부분 실패 유지, Trace 상태별 조회, 만료/ID/section 오류 |
| `global/config/OpenApiIntegrationTests.java` | 실제 생성된 section 파라미터·oneOf·스키마·예시 및 Trace 명세 |

보고 파일: `cross-report-trace.md`.

## 테스트 결과

- `./gradlew compileJava compileTestJava`: 성공.
- `./gradlew test`: **94건 성공, 실패 0, 오류 0, 건너뜀 0**.
- Trace 전용 15건, 결과 API 통합 16건, 파이프라인/API 통합 17건, OpenAPI 통합 5건을 포함한다.
- 기존 MATCHED/CONTRADICTED/MIXED/UNCONFIRMED 판정, 일치/상반 건수, UNRELATED 제외, 최대 5개 비교, 출처 평가, 실패 시 부분 결과 등의 회귀 테스트도 통과했다.
- 20개 후보 시나리오에서 수집 성공 10개, 수집 한도로 미처리 10개, LLM 미선정 9개 및 최종 선정 1개를 구분했다. 미처리 후보의 evidenceId/llmSelected/comparison은 null이다.
- Trace 저장 callback이 예외를 던지는 경우에도 정상 실행과 동일한 Result/warnings를 반환하는 것을 비교했다.
- 외부 유료 API 호출 없이 SearchClient/DocumentFetcher/StructuredLlm 또는 WebTransport를 모의 처리했다. HTTP API·Swagger 검증은 로컬 서버를 사용했다.
- `git diff --check`: 통과.
- HTML 결과: `build/reports/tests/test/index.html`.

## 기존 정책을 유지한 사항 및 별도 개선 후보

분석 정책 변경이 필요한 문제로 이번 구현이 막히지는 않았다. 다음 사항은 기존 동작을 유지했다.

- 최대 20개 검색 결과 중 본문 수집은 최대 10회이므로, 뒤쪽 검색 제공자/쿼리의 후보가 수집되지 않을 수 있다. 순서 조정이나 수집 확대는 별도의 검색 정책 변경이다.
- 후보끼리 본문이 같으면 기존 EvidencePool이 동일 evidenceId로 연결한다. 후보를 완전히 제외하는 방식으로 바꾸지 않았다.
- 선정·미선정 이유는 기존 ReportSelection에 없다. 이유가 필요하면 LLM 응답 DTO·프롬프트·근거 검증 정책을 별도 설계해야 한다.
- 외부 호출의 안전한 예외가 제공하는 HTTP 상태·인증/한도/연결/응답 검증 사유까지만 보존한다. 원격 응답 전문이나 내부 재시도별 이력을 추가하는 변경은 하지 않았다.
