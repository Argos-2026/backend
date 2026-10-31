package org.argos.argos_backend.external.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ExternalCalls {
    private static final Logger log = LoggerFactory.getLogger(ExternalCalls.class);
    private static final Set<String> QUOTA_CODES = Set.of("insufficient_quota", "credit_balance_exhausted",
        "billing_hard_limit_reached", "usage_limit_reached", "organization_spend_limit_exceeded",
        "project_spend_limit_exceeded", "organization_usage_limit_exceeded");
    private final WebTransport transport;
    private final JsonMapper json;
    public ExternalCalls(WebTransport transport, JsonMapper json) { this.transport = transport; this.json = json; }
    public WebTransport.Response request(URI uri, String method, Map<String, String> headers, byte[] body,
                                         Duration timeout, String errorCode, AnalysisContext context) {
        String provider = "api.openai.com".equals(uri.getHost()) ? "OpenAI" : "외부 서비스";
        String message = provider + " 연결 또는 응답 대기 중 오류가 발생했습니다. 네트워크 상태를 확인해 주세요.";
        for (int attempt = 0; attempt < 2; attempt++) {
            context.check();
            try {
                var response = transport.exchange(uri, method, headers, body, context.timeout(timeout), 5 * 1024 * 1024);
                if (response.status() >= 200 && response.status() < 300) return response;
                RemoteFailure failure = classify(provider, response);
                message = failure.message();
                log.warn("External request failed provider={} status={} stage={} retryable={}", provider, response.status(), context.stage(), failure.retryable());
                if (!failure.retryable()) break;
            } catch (ApacheWebTransport.TransportFailure e) {
                // One bounded retry; never expose the remote response or credentials.
            }
            if (attempt == 0) {
                try { Thread.sleep(Math.min(250, context.timeout(Duration.ofMillis(250)).toMillis())); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); context.check(); }
            }
        }
        throw new AnalysisFailure(errorCode, message);
    }

    private RemoteFailure classify(String provider, WebTransport.Response response) {
        int status = response.status();
        boolean quota = false;
        if (provider.equals("OpenAI")) {
            try {
                var error = json.readTree(response.body()).path("error");
                quota = QUOTA_CODES.contains(error.path("code").asString(""))
                    || "insufficient_quota".equals(error.path("type").asString(""));
            } catch (RuntimeException ignored) { /* Non-JSON failures still get a safe status-based message. */ }
        }
        String message;
        if (quota) message = "OpenAI API 크레딧 또는 사용 한도가 부족합니다. API 결제·잔액·사용 한도를 확인해 주세요.";
        else message = switch (status) {
            case 400 -> provider + " 요청 형식 또는 모델 옵션이 올바르지 않습니다. 서버의 API 요청 설정을 확인해야 합니다.";
            case 401 -> provider + " API 인증에 실패했습니다. 서버에 설정한 API 키를 확인해 주세요.";
            case 403 -> provider + " API 접근 권한이 없습니다. 프로젝트·모델·서비스 권한을 확인해 주세요.";
            case 404 -> provider + " 모델 또는 API 경로를 찾을 수 없습니다. 모델 이름과 접근 권한을 확인해 주세요.";
            case 429 -> provider + " API 호출 속도 제한에 도달했습니다. 잠시 후 다시 시도해 주세요.";
            default -> status >= 500 ? provider + "에 일시적인 장애가 발생했습니다. 잠시 후 다시 시도해 주세요."
                : provider + " 요청이 거부되었습니다. API 설정과 서비스 이용 상태를 확인해 주세요.";
        };
        return new RemoteFailure(message + " (HTTP " + status + ")", !quota && (status == 429 || status >= 500));
    }

    private record RemoteFailure(String message, boolean retryable) {}
}
