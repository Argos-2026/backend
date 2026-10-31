# ARGOS Backend

뉴스 URL에서 핵심 주장 최대 3개를 추출하고, 외부 원문으로 사실 검증과 기사 내부 정보원의 IMVAIN 6개 기준 평가를 수행하는 Spring Boot 백엔드입니다.

**분석 요청·전체 비동기 분석 처리·상태 조회·결과 조회의 3단계 구현을 완료했습니다.** 작업 상태·결과는 메모리에 저장합니다. 실제 서비스 인증과 분석 품질은 API 키 설정 후 확인해야 합니다.

| Method | 경로 | 기능 |
|---|---|---|
| POST | `/api/v1/analyses` | 분석 작업 접수·실행 |
| GET | `/api/v1/analyses/{analysisId}` | 상태·실행 단계·경고·실패 이유 조회 |
| GET | `/api/v1/analyses/{analysisId}/result` | 기사·주장별 판정·근거·정보원 평가 조회 |

## 프로젝트 구조

```text
src/main/java/org/argos/argos_backend/
├── ArgosBackendApplication.java
├── analysis/
│   ├── controller/   # 분석 요청·상태·결과 API (docs/에 Swagger 인터페이스)
│   ├── dto/          # API 접수·상태 응답
│   ├── service/      # 작업 실행·조회·분석 파이프라인
│   ├── domain/       # 분석 상태·결과 모델·작업 상태 전이·실행 컨텍스트
│   └── repository/   # 메모리 작업 저장소
├── external/
│   ├── http/         # HTTP 전송·재시도·URL 및 DNS 검증
│   ├── document/     # HTML·PDF 수집과 본문 정규화
│   ├── search/       # NAVER API HUB·Brave 검색 연동
│   └── llm/          # OpenAI 연동·구조화 출력·LLM 입출력 모델
└── global/
    ├── config/       # 환경변수·Clock·CORS 설정
    ├── dto/          # 공통 오류 응답
    └── exception/    # API 예외와 오류 응답 처리

src/main/resources/
├── application.properties
└── prompts/analysis/ # 추출·선별·검증·정보원 평가·요약 프롬프트
```

`src/test/java`는 운영 코드의 패키지 구조를 따릅니다. 여러 테스트에서 쓰는 설정·결과 데이터는 `support/AnalysisFixtures`에서 관리합니다. 컨트롤러는 API 입출력, 서비스는 작업 흐름, 도메인은 분석 데이터와 상태 전이, 저장소는 메모리 보관을 담당합니다. 외부 서비스 연동 코드와 공통 설정은 분석 기능 패키지에서 분리했습니다.

## 로컬 준비

- Java 21
- OpenAI API 키와 호출 가능한 모델·크레딧
- NAVER API HUB 뉴스 검색의 Client ID·Client Secret
- Brave Web Search용 API 키

프로젝트 루트에서 실행합니다.

```powershell
Copy-Item -LiteralPath .env.example -Destination .env
```

이미 `.env`가 있으면 복사하지 말고 기존 파일을 편집합니다. 발급받은 키를 다음 항목에 입력합니다.

```dotenv
OPENAI_API_KEY=
OPENAI_MODEL=gpt-5.4-mini
NAVER_CLIENT_ID=
NAVER_CLIENT_SECRET=
BRAVE_API_KEY=
```

`KEY=value` 형식을 사용하고 값을 따옴표로 감싸거나 `export`를 붙이지 않습니다. `.env`는 Git 추적·Docker 빌드에서 제외됩니다. API 키는 채팅이나 프론트엔드에 넣지 않습니다.

Spring은 작업 디렉터리의 `.env`를 자동으로 읽습니다. 운영체제 환경변수가 파일 값보다 우선합니다. 값을 바꾼 뒤에는 앱을 재시작합니다. IDE에서도 작업 디렉터리를 프로젝트 루트로 설정합니다.

키가 없어도 앱 기동과 자동 테스트는 가능합니다. 필수 키가 비어 있으면 분석 요청에는 `503 SERVICE_UNAVAILABLE`을 반환하고 외부 API는 호출하지 않습니다.

네이버 신규 발급은 [NAVER API HUB](https://guide.ncloud-docs.com/docs/apihub-application) 기준이며 네이버 로그인용 키와 다릅니다. [뉴스 검색 공식 문서](https://api.ncloud-docs.com/docs/naver-api-hub-search-news), [OpenAI API 시작](https://developers.openai.com/api/docs/quickstart), [Brave 인증 안내](https://api-dashboard.search.brave.com/documentation/guides/authentication)를 참고하시면 됩니다.

## 실행·테스트

```powershell
.\gradlew.bat test
.\gradlew.bat bootRun
```

Linux/macOS에서는 `./gradlew test`, `./gradlew bootRun`을 사용합니다. 실행 JAR은 `./gradlew bootJar`로 빌드합니다.

자동 테스트는 실제 키와 유료 API 호출을 사용하지 않습니다. `.env`가 테스트 결과에 영향을 주지 않도록 통합 테스트의 외부 인증정보와 HTTP 응답은 별도로 주입합니다.

Docker를 사용할 때에도 먼저 `.env`를 작성합니다.

```powershell
docker compose up --build
```

Compose가 파일의 환경변수를 컨테이너에 전달하며, `.env` 자체는 이미지에 들어가지 않습니다. EC2의 기존 Compose 설정은 로컬 파일과 별도이므로 서버에도 환경변수를 주입해야 합니다.

## Swagger API 문서

애플리케이션을 실행한 뒤 아래 주소에서 API 명세와 요청·응답 예시를 확인할 수 있습니다. 문서 조회에는 외부 API 키가 필요하지 않습니다.

- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- OpenAPI JSON: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- OpenAPI YAML: [http://localhost:8080/v3/api-docs.yaml](http://localhost:8080/v3/api-docs.yaml)

Swagger 설명은 `analysis/controller/docs/AnalysisApiDocs.java` 인터페이스에서 관리하며 `AnalysisController`가 이를 구현합니다. 실제 컨트롤러에는 요청 매핑과 처리 코드만 둡니다. API 제목·버전·서버 주소는 `global/config/OpenApiConfiguration.java`에서 설정합니다.

Swagger UI에서 POST를 실행해 발급받은 `analysisId`를 상태 조회에 입력합니다. `COMPLETED` 또는 `PARTIAL_COMPLETED`가 되면 같은 ID로 결과를 조회합니다. POST의 실제 분석에는 `.env`에 설정한 외부 API 키가 필요하며 서비스 사용량이 발생할 수 있습니다. 예시의 `news.example.com`은 설명용 주소이므로 실제 기사 URL로 바꿉니다.

## 분석 요청

앱을 실행한 상태에서 다른 PowerShell 터미널로 요청합니다. URL은 실제 접근 가능한 뉴스 기사로 바꿉니다.

```powershell
$body = @{ articleUrl = 'https://실제-뉴스-도메인/기사-경로' } | ConvertTo-Json

$accepted = Invoke-RestMethod `
    -Method Post `
    -Uri 'http://localhost:8080/api/v1/analyses' `
    -ContentType 'application/json' `
    -Body $body

$accepted
```

성공 시 HTTP `202`와 다음 구조를 반환합니다.

```json
{
  "analysisId": "a0f02db8-c8af-48db-b9fd-5d6f62303a11",
  "status": "QUEUED",
  "createdAt": "2026-10-31T08:00:00Z",
  "expiresAt": "2026-11-01T08:00:00Z"
}
```

`202`는 작업 접수 성공입니다. 이후 실제 분석은 백그라운드에서 진행하며 아래 상태 조회 API로 진행 상황을 확인할 수 있습니다. 최종 분석 내용은 내부 저장소에 보관하고 결과 조회 API로 반환합니다.

요청 오류는 `{ "error": { "code": "...", "message": "..." } }` 형식입니다.

| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `INVALID_REQUEST` | JSON 또는 필드 타입 오류 |
| 400 | `INVALID_URL` | URL 누락·형식 오류 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | JSON이 아닌 요청 |
| 422 | `UNSUPPORTED_URL` | 내부망 주소·지원하지 않는 프로토콜·포트 |
| 429 | `RATE_LIMIT_EXCEEDED` | 동시 작업·대기열·메모리 보관 한도 초과 |
| 503 | `SERVICE_UNAVAILABLE` | 키 미설정 또는 작업 접수 불가 |
| 500 | `INTERNAL_SERVER_ERROR` | 예상하지 못한 요청 처리 오류 |

## 분석 상태 조회

분석 요청에서 반환된 ID를 사용합니다. 이 조회는 외부 API를 호출하지 않으며 API 키 설정 여부와 관계없이 기존 작업을 조회할 수 있습니다.

```powershell
$status = Invoke-RestMethod `
    -Uri "http://localhost:8080/api/v1/analyses/$($accepted.analysisId)"

$status | ConvertTo-Json -Depth 10
```

진행 중 응답 예시입니다. 작업 상태와 단계·경고·오류는 하나의 스냅샷으로 읽습니다.

```json
{
  "analysisId": "a0f02db8-c8af-48db-b9fd-5d6f62303a11",
  "status": "RUNNING",
  "activeStages": ["RETRIEVING_EVIDENCE"],
  "expiresAt": "2026-11-01T08:00:00Z",
  "warnings": [],
  "error": null
}
```

| 상태 | 의미 | 클라이언트 동작 |
|---|---|---|
| `QUEUED` | 실행 대기 | 2초 후 재조회 |
| `RUNNING` | 분석 수행 중 | 2초 후 재조회 |
| `COMPLETED` | 정상 완료 | 폴링 종료 |
| `PARTIAL_COMPLETED` | 사용 가능한 결과가 있으나 일부 실패 | 폴링 종료·`warnings` 확인 |
| `FAILED` | 사용 가능한 결과를 생성하지 못함 | 폴링 종료·`error` 확인 |

대기·종료 상태의 `activeStages`는 `[]`입니다. `error`는 `FAILED`에서만 객체이며, 분석 자체가 실패해도 상태 조회가 성공했다면 HTTP `200`입니다. `warnings[]`와 `error` 객체에는 `code`, `stage`, `message`가 들어갑니다. 조회 응답은 `Cache-Control: no-store`를 사용합니다.

| 조회 오류 HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `INVALID_ANALYSIS_ID` | 표준 UUID 형식이 아닌 ID |
| 404 | `ANALYSIS_NOT_FOUND` | 없는 ID, 접수 후 24시간 만료, 서버 재시작으로 유실 |
| 500 | `INTERNAL_SERVER_ERROR` | 상태 조회 처리 중 서버 오류 |

조회 오류도 `{ "error": { "code": "...", "message": "..." } }` 형태입니다. `404`이면 폴링을 중단하고 기사 URL을 다시 제출합니다. 재요청은 새 분석을 실행하므로 외부 API 사용량이 발생할 수 있습니다.

## 분석 결과 조회

`COMPLETED` 또는 `PARTIAL_COMPLETED`이면 결과를 조회합니다.

```powershell
$result = Invoke-RestMethod `
    -Uri "http://localhost:8080/api/v1/analyses/$($accepted.analysisId)/result"

$result | ConvertTo-Json -Depth 20
```

| 결과 필드 | 내용 |
|---|---|
| `analysisId`, `status` | 분석 ID와 완료·부분 완료 상태 |
| `analysisOutcome` | `ANALYZED` 또는 `NO_VERIFIABLE_CLAIMS` |
| `article` | 제목·원문 URL·언론사·작성자·발행 시각 |
| `summary` | 종합 코멘트·사실 검증 요약·정보원 평가 요약·미확인 내용 |
| `claims` | 주장별 판정·이유·연결 근거·기사 내부 정보원 ID |
| `evidence` | 원문 제목·발행 기관·URL·발행 시각·검증에 사용한 발췌 |
| `articleSources` | 기사 내부 정보원별 인용 문장과 IMVAIN 6개 항목·이유·근거 ID |
| `warnings` | 부분 실패의 코드·단계·설명 |

정보원 평가 실패 시 `articleSources`와 `summary.sourceAssessmentOverview`는 `null`입니다. 평가를 성공적으로 수행했지만 정보원을 찾지 못한 경우에는 `articleSources=[]`입니다. 검증 가능한 주장이 없으면 `claims=[]`와 `NO_VERIFIABLE_CLAIMS`를 반환합니다.

주장·정보원 카드의 근거는 `evidenceId`로 `evidence[]`와 연결합니다. 원문 보기에는 근거의 `url`을 사용하고, 정보원 카드의 근거 목록은 IMVAIN 6개 항목의 `evidenceIds`를 중복 제거해 표시합니다. 판정별 개수와 기준별 충족·미충족·확인 불가 개수는 배열에서 계산할 수 있습니다. 권위성과 정보성은 각각 표시하며 합산 신뢰도 점수는 제공하지 않습니다.

결과 조회는 저장된 결과만 읽으며 검색·LLM을 다시 호출하지 않습니다. 기사 전문과 API 키는 응답에 포함하지 않고, 성공·API 오류 응답에 `Cache-Control: no-store`를 적용합니다.

| 결과 조회 HTTP | 코드 또는 응답 | 조건 |
|---|---|---|
| 200 | 분석 결과 | 완료 또는 부분 완료 |
| 400 | `INVALID_ANALYSIS_ID` | 잘못된 UUID |
| 404 | `ANALYSIS_NOT_FOUND` | 없는 ID·만료·서버 재시작 유실 |
| 409 | `ANALYSIS_NOT_READY` | 대기·분석 중 |
| 409 | `ANALYSIS_FAILED` | 분석 전체 실패. 상태 조회에서 구체적인 이유 확인 |
| 500 | `INTERNAL_SERVER_ERROR` | 결과 조회 처리 오류 |

## 요청부터 결과까지 한 번에 실행

다음 예제는 실제 기사 URL을 입력해 새 분석을 생성합니다. 실제 API 사용량이 발생할 수 있습니다.

```powershell
$requestBody = @{ articleUrl = 'https://실제-뉴스-도메인/기사-경로' } | ConvertTo-Json
$accepted = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8080/api/v1/analyses' `
    -ContentType 'application/json' -Body $requestBody

do {
    $analysisStatus = Invoke-RestMethod `
        -Uri "http://localhost:8080/api/v1/analyses/$($accepted.analysisId)"
    Write-Output "$($analysisStatus.status): $($analysisStatus.activeStages -join ', ')"
    if ($analysisStatus.status -in @('QUEUED', 'RUNNING')) {
        Start-Sleep -Seconds 2
    }
} while ($analysisStatus.status -in @('QUEUED', 'RUNNING'))

if ($analysisStatus.status -eq 'FAILED') {
    throw $analysisStatus.error.message
}

$result = Invoke-RestMethod `
    -Uri "http://localhost:8080/api/v1/analyses/$($accepted.analysisId)/result"
$result | ConvertTo-Json -Depth 20
```

`PARTIAL_COMPLETED`이면 결과와 `warnings`를 함께 확인합니다. Ctrl+C로 폴링을 중단해도 이미 접수한 서버 분석 작업은 계속 실행됩니다.

## 프론트엔드 CORS 설정

개발 기본값은 `http://localhost:3000`, `http://localhost:5173`입니다. `.env` 또는 서버 환경변수에서 쉼표로 구분한 정확한 origin 목록을 설정하고 앱을 재시작합니다.

```dotenv
CORS_ALLOWED_ORIGINS=https://frontend.example.com,https://admin.example.com
```

운영에서는 실제 프론트엔드 주소로 교체합니다. origin에는 경로·마지막 슬래시·와일드카드를 넣지 않습니다. 빈 값으로 설정하면 다른 origin의 브라우저 접근을 허용하지 않습니다. GET·POST와 JSON 요청의 preflight를 지원하며 쿠키 인증은 사용하지 않습니다. 허용하지 않은 origin 또는 메서드의 CORS 요청은 403으로 차단됩니다. 이 경우 브라우저에서 API JSON 오류 본문을 읽지 못할 수 있습니다.

## 분석 동작

1. 기사 HTML 수집·정규화: 제목·언론사·작성자·발행 시각·본문 추출
2. LLM으로 핵심 주장·검증 질문·검색어와 주요 내부 정보원 추출
3. NAVER API HUB·Brave 검색, 외부 HTML·텍스트 PDF 원문 수집, 중복 제거
4. 주장별 근거 선별·인용문 확인
5. `SUPPORT / REFUTE / CONFLICT / INSUFFICIENT` 판정
6. 정보원 외부 확인과 IMVAIN 6개 항목 평가
7. 종합 요약·근거·정보원 평가·경고 통합 및 메모리 저장

프롬프트는 `src/main/resources/prompts/analysis/`에 단계별로 있습니다. LLM 출력에는 닫힌 JSON Schema를 적용하고, ID·인용 원문·판정에 필요한 근거 관계를 다시 검사합니다. 모델의 의미적 판단 정확성은 실제 기사·공식 자료로 추가 평가해야 합니다.

검색 API 호출 자체가 실패한 경우와 정상 검색 후 근거가 부족한 경우를 구분합니다. 정상적인 근거 부족과 주장 0개는 작업 실패가 아닙니다. 사용 가능한 판정은 있으나 검색 일부 또는 정보원 평가 등이 실패하면 부분 완료를 기록합니다.

발언 검증에서는 **발언을 했다는 사실**과 **발언 내용 자체의 사실 여부**를 나눕니다. 예를 들어 `김민수가 예산이 30% 줄었다고 말했다`는 실제 감소율 자료만으로 뒷받침할 수 없습니다. 추출·검색·선별·판정 단계에서 같은 검증 대상을 유지하고, 발언자를 명시한 원문 인용을 검사합니다. 발언 근거가 없거나 전체 주장을 확인하지 못하면 `INSUFFICIENT`로 반환하고 관련 내용 자료는 `CONTEXT`로 연결합니다. 내부 검증용 분류·인용 필드는 공개 응답에 추가하지 않습니다. 이 과정에도 AI의 의미적 판단이 포함되므로 실제 기사로 품질을 확인해야 합니다.

## 기본 제한과 운영 전 확인

| 항목 | 기본값 |
|---|---|
| 동시 실행 / 대기열 | 2개 / 8개 |
| 메모리 보관 작업 | 최대 100개 |
| 작업 보관 | 접수부터 24시간, 서버 재시작 시 유실 |
| 실행 제한시간 | 실행 시작부터 10분, 보관 기한 이내 |
| 외부 문서 / OpenAI 요청 제한시간 | 15초 / 60초 |
| 문서 다운로드 / 분석 본문 | 문서당 5MB / 30,000자 |
| PDF 처리 | 텍스트 추출 가능한 PDF 앞 50페이지, OCR 제외 |
| 주장 / 주요 정보원 | 최대 3개 / 최대 6개 |

작업 수·시간은 `.env.example`의 `ANALYSIS_*` 값으로 조정할 수 있습니다. 작업 실행 제한시간은 보관 기한보다 짧아야 합니다. 로컬 메모리 방식이므로 단일 인스턴스를 전제로 합니다.

유료벽·접근 차단·브라우저 렌더링이 필요한 사이트는 본문 확보에 실패할 수 있습니다. 기사와 근거는 공개 웹의 HTTP/HTTPS 기본 포트에서만 수집하며 내부망·특수 주소·위험한 리다이렉트는 거부합니다.

실제 사용 전에는 키·크레딧·모델 권한·검색 API 한도를 확인하고, 알고 있는 기사와 공식 자료로 결과를 대조합니다. 운영 배포 계정과 프론트엔드 연동은 별도 준비가 필요합니다.

## OpenAI 요청 실패 확인

상태 응답이 `FAILED`, `error.code=LLM_FAILED`이면 `error.message`에서 원인을 확인합니다. 작업 실패 이유와 HTTP 상태 조회의 성공 여부는 별개이므로 상태 조회는 HTTP 200을 반환할 수 있습니다.

| 오류 메시지 | 확인할 것 |
|---|---|
| HTTP 401 · API 인증 실패 | 서버가 실제로 읽는 `OPENAI_API_KEY` |
| HTTP 403 · 접근 권한 없음 | API 프로젝트·모델 사용 권한 |
| HTTP 404 · 모델 또는 경로 없음 | `OPENAI_MODEL`과 모델 접근 권한 |
| HTTP 429 · 크레딧 또는 사용 한도 부족 | API 플랫폼의 결제·크레딧 잔액·사용 한도 |
| HTTP 429 · 호출 속도 제한 | 동시 요청과 토큰 사용량, 잠시 후 새 분석 요청 |
| HTTP 400 · 요청 형식 오류 | 서버의 요청 옵션·구조화 출력 스키마 |

`credit_balance_exhausted`·`insufficient_quota` 등의 잔액·한도 오류는 자동 재시도하지 않습니다. 일시적인 호출 속도 제한·서버 장애·연결 실패만 최대 1회 재시도합니다. 외부 서비스의 오류 원문과 API 키는 응답·로그에 노출하지 않습니다.

잔액·권한 문제를 해결한 뒤에는 POST로 새 분석을 생성합니다. 이미 실패한 분석 ID는 자동으로 재실행되지 않습니다. 환경변수를 바꿨다면 서버도 재시작합니다.
