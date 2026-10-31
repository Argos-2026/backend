# Trace null 기록 버그 수정 및 NAVER 0건 진단

## 수정 범위

프로덕션 변경은 다음 두 파일뿐이다. 이전 작업에서 추가한 API·DTO·파이프라인 변경은 이번 수정 범위와 구분한다.

- `src/main/java/org/argos/argos_backend/analysis/domain/CrossReportTrace.java`: `selection()`의 필터에 `c.evidenceId != null` 조건 추가. 제외·미처리 후보를 건너뛰고 뒤의 정상 후보와 재사용 근거 후보 모두 선정 여부를 기록한다.
- `src/main/java/org/argos/argos_backend/analysis/domain/AnalysisContext.java`: 기존 RuntimeException 격리를 유지하며 `TRACE_RECORDING_FAILED` 경고 로그 추가. 출력 인수는 Stage enum과 예외 클래스명뿐이다. 예외 메시지·원인·스택 트레이스·기사/주장/쿼리/URL·인증정보는 로거에 전달하지 않는다.

로그 예시:

```text
code=TRACE_RECORDING_FAILED stage=SELECTING_EVIDENCE exceptionType=IllegalStateException
```

검색·본문 수집·기사 선정·비교·집계 코드, 쿼리 생성 및 판정 프롬프트, API DTO/계약은 이번 작업에서 변경하지 않았다. AnalysisPipeline, SearchClient, AnalysisController, CrossReportTraceResponse 및 extract/select/verify 프롬프트의 수정 전후 SHA-1이 동일함을 확인했다.

## 회귀 검증

- `analysis/domain/CrossReportTraceTests.java` 추가: null evidenceId 후보가 앞/중간/뒤에 위치하는 3개 사례, 동일 evidenceId를 여러 후보·주장이 재사용하는 사례. 선정 후보의 true/LLM_SELECTED/COMPARED와 미선정·제외 후보의 상태를 함께 확인한다.
- `analysis/domain/AnalysisContextTests.java` 추가: Trace 갱신 실패와 저장 callback 실패를 각각 발생시켜 예외 미전파·warnings 유지 및 로그 비노출을 확인한다. 예외 메시지와 원인에 가짜 API 키·본문·이름·이메일을 넣고, 로그에는 안전한 인수만 있고 Throwable도 첨부되지 않는지 검증한다.
- `analysis/service/AnalysisPipelineTraceTests.java` 보강: 제외 후보 사이의 선정 후보, 실제 본문 중복에 따른 근거 재사용, 앞 후보 수집 실패 뒤 정상 선정 후보의 이벤트 누락 방지. MIXED 판정·일치/상반 건수 유지 및 기존 저장 실패 격리 테스트도 통과했다.
- `external/search/SearchClientTests.java` 추가: NAVER 모의 응답 7개 사례로 기존 파서의 원본 items와 반환 URL 수를 확인했다. 실제 검색 코드는 변경하지 않았다.
- `./gradlew test`: **108건, 실패 0, 오류 0, 건너뜀 0**. 기존 API 응답·Swagger 회귀 테스트 포함.
- `git diff --check`: 통과.

## NAVER 엔드포인트와 응답 스키마 확인

현재 SearchClient는 아래 엔드포인트에 UTF-8로 인코딩한 query를 전송한다.

```text
GET https://naverapihub.apigw.ntruss.com/search/v1/news
query=<기존 쿼리>&display=5&sort=sim&format=json
```

사용 중인 헤더 이름은 `X-NCP-APIGW-API-KEY-ID`, `X-NCP-APIGW-API-KEY`다. 값은 보고서·로그·진단 출력에 포함하지 않는다.

[NAVER API HUB 뉴스 검색 공식 문서](https://api.ncloud-docs.com/docs/naver-api-hub-search-news)의 경로·파라미터·JSON 구조와 현재 구현이 일치한다. 주요 응답 필드는 `lastBuildDate`, `total`, `start`, `display`, `items`이며, 각 item에는 `title`, `originallink`, `link`, `description`, `pubDate`가 있다. `total`은 전체 검색 건수이고 현재 페이지의 items 개수와 다르다.

SearchClient의 실제 변환은 다음과 같다.

1. 최상위 `items`가 배열인지 확인한다. 아니면 SEARCH_FAILED다.
2. `originallink`가 비어 있지 않으면 공백을 제거해 사용한다. 없으면 `link`로 대체한다.
3. 두 URL 필드가 모두 없거나 공백이면 항목을 버린다.
4. 최대 5개까지 반환한다. URL 중복 제거·안전성 검증·본문 수집은 이 단계에서 하지 않는다.

따라서 이 단계의 ‘유효 URL’은 **비어 있지 않은 URL 문자열**이다. 접속 가능한 공개 기사 URL임을 뜻하지 않는다. 문법이 잘못된 originallink도 문자열이 있으면 우선 선택되며, 뒤의 collect()/SafeUrlPolicy에서 거부될 수 있다. 이 동작도 변경하지 않았다.

모의 응답으로 확인한 결과:

| 응답 조건 | 원본 items 수 | 반환 후보 수 | 해석 |
| --- | --- | --- | --- |
| 정상 원문 URL 5개 | 5 | 5 | 손실 없음 |
| 원문 URL 1개, link 대체 1개, 두 URL 누락/공백 2개 | 4 | 2 | URL 없는 두 항목만 제외 |
| 모든 항목에 URL 누락/공백 | 2 | 0 | 원본 items가 있어도 성공 0건 가능 |
| 빈 items 배열 | 0 | 0 | 정상적인 빈 검색 응답 |
| items 필드 없음 | 해당 없음 | 실패 | 성공 0건으로 변환하지 않음 |
| 비어 있지 않은 잘못된 URL 문자열 | 1 | 1 | 이후 수집 단계의 검증 대상 |
| 제한보다 많은 항목을 준 모의 응답 | 7 | 5 | 기존 요청 개수 제한 |

현재 셸의 NAVER_CLIENT_ID/NAVER_CLIENT_SECRET이 설정되어 있지 않고 작업 폴더에 .env도 없어 실 API 요청은 수행하지 않았다. 실제 report-1의 응답 원문도 제공되지 않았다. 따라서 실제 0건이 원격 검색 결과인지 URL 필드 누락인지 아직 확정할 수 없다. 인증/HTTP 실패는 보통 FAILED 및 resultCount=null로 기록되므로 SUCCEEDED/0과 구분해야 한다.

## 네 쿼리를 직접 점검하는 로컬 방법

아래 절차는 프로덕션 코드를 수정하지 않는다. 사용자가 실행할 때 NAVER 검색을 쿼리별 1회, 총 4회 호출하며 LLM·기사 본문은 호출하지 않는다. 기본 `display=5&sort=sim&format=json`을 유지한다. 공식 응답의 전체 본문·기사 제목·URL·인증 헤더를 출력하거나 저장하지 않고 개수만 출력한다.

기존 분석의 Trace를 `/tmp/argos-trace.json`에 저장해 두면 report-1의 실제 exactQuery를 그대로 읽는다. 예:

```sh
curl --fail --silent --show-error \
  'http://localhost:8080/api/v1/analyses/실제-분석-UUID/cross-report/trace' \
  --output /tmp/argos-trace.json
```

파일이 없으면 기존 report-1 exactQuery를 숨김 입력으로 받는다. 예시 쿼리로 대체하지 않는다. API 키는 이미 설정된 환경변수에서 읽거나 터미널의 숨김 입력으로 받는다. 아래 Python 블록을 로컬 터미널에서 실행한다.

```sh
python3 - <<'PY'
import getpass
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import warnings

warnings.simplefilter("error", getpass.GetPassWarning)

ENDPOINT = "https://naverapihub.apigw.ntruss.com/search/v1/news"

# Redirects must not forward authentication headers to another destination.
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def nonblank(value):
    return value.strip() if isinstance(value, str) else ""

def summarize(root):
    if not isinstance(root, dict) or not isinstance(root.get("items"), list):
        return {"schema": "ITEMS_NOT_ARRAY"}
    items = root["items"]
    original = fallback = missing = 0
    for item in items:
        if not isinstance(item, dict):
            return {"schema": "ITEM_NOT_OBJECT"}
        if nonblank(item.get("originallink")):
            original += 1
        elif nonblank(item.get("link")):
            fallback += 1
        else:
            missing += 1
    nonblank_urls = original + fallback
    return {
        "schema": "OK",
        "total": root.get("total") if type(root.get("total")) is int else None,
        "items_count": len(items),
        "original_url_count": original,
        "link_fallback_count": fallback,
        "missing_url_count": missing,
        "nonblank_url_count": nonblank_urls,
        "returned_at_current_limit": min(5, nonblank_urls),
    }

def main():
    trace_path = Path("/tmp/argos-trace.json")
    if trace_path.is_file():
        try:
            trace = json.loads(trace_path.read_text(encoding="utf-8"))
            exact = next(c["exactQuery"] for c in trace["claims"] if c["claimId"] == "report-1")
        except Exception:
            raise SystemExit("Trace에서 report-1 exactQuery를 읽지 못했습니다.")
    else:
        exact = getpass.getpass("기존 report-1 exactQuery (숨김 입력): ")
    if not isinstance(exact, str) or not exact.strip():
        raise SystemExit("report-1의 실제 exactQuery가 필요합니다.")

    client_id = os.environ.get("NAVER_CLIENT_ID") or getpass.getpass("NAVER Client ID (숨김 입력): ")
    client_secret = os.environ.get("NAVER_CLIENT_SECRET") or getpass.getpass("NAVER Client Secret (숨김 입력): ")
    if not client_id or not client_secret:
        raise SystemExit("NAVER 인증정보가 필요합니다.")
    headers = {
        "X-NCP-APIGW-API-KEY-ID": client_id,
        "X-NCP-APIGW-API-KEY": client_secret,
    }
    queries = [
        ("q1", "현대모비스 의왕연구소 화재"),
        ("q2", "의왕연구소 배터리 연구동 화재"),
        ("q3", "현대모비스 의왕연구소 화재 2026 10월 20일"),
        ("report1_exact", exact),
    ]
    opener = urllib.request.build_opener(NoRedirect())
    for label, query in queries:
        params = urllib.parse.urlencode({"query": query, "display": 5, "sort": "sim", "format": "json"})
        request = urllib.request.Request(ENDPOINT + "?" + params, headers=headers)
        try:
            with opener.open(request, timeout=15) as response:
                body = response.read(5 * 1024 * 1024 + 1)
                if len(body) > 5 * 1024 * 1024:
                    raise ValueError()
                root = json.loads(body)
                result = {"label": label, "http_status": response.status, **summarize(root)}
        except urllib.error.HTTPError as error:
            result = {"label": label, "http_status": error.code, "status": "HTTP_ERROR"}
            error.close()
        except (urllib.error.URLError, TimeoutError):
            result = {"label": label, "status": "CONNECTION_OR_TIMEOUT_ERROR"}
        except Exception:
            result = {"label": label, "status": "LOCAL_OR_RESPONSE_ERROR"}
        print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
PY
```

각 label과 검색어의 대응은 블록 안의 queries에 표시했다. report1_exact는 Trace에서 가져온 문자열을 변경하지 않고 전송한다. 진단 출력에서는 실제 query도 출력하지 않으므로 해당 쿼리에 개인정보가 포함되어 있어도 출력하지 않는다. 키와 헤더 값을 확인하기 위한 `env`, `print(headers)`, curl verbose, 원격 오류 본문 출력은 필요하지 않다.

결과 판독:

- HTTP 200 + `items_count=0`: 서버가 빈 배열을 반환했다. `total`도 함께 확인한다.
- HTTP 200 + `items_count>0`, `nonblank_url_count=0`: 파서에서 사용하는 두 URL 필드가 모두 비어 있다.
- `items_count=nonblank_url_count<=5`: URL 선택 단계에서 개수 손실이 없다.
- `nonblank_url_count>returned_at_current_limit`: 기존 최대 5개 제한이다.
- `ITEMS_NOT_ARRAY`: 현재 SearchClient라면 SEARCH_FAILED로 처리할 응답이다.
- HTTP 400/401/403/429/5xx 등: 빈 검색 결과와 구분할 오류다. 이 절차는 인증정보나 원격 오류 메시지를 출력하지 않는다.

이 점검은 쿼리의 자동 단순화·재작성·fallback이나 프롬프트 변경을 적용하지 않는다. 네 쿼리의 실측 결과를 비교한 뒤 검색어 제약·응답 형식·수집 단계 문제를 별도로 판단한다. 로컬 진단은 검색 시점과 환경이 달라 과거 요청의 원본 응답을 완전히 재현하지는 못한다.
