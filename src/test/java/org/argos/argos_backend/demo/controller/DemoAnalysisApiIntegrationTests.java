package org.argos.argos_backend.demo.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "argos.openai-api-key=", "argos.naver-client-id=", "argos.naver-client-secret=", "argos.brave-api-key="
})
class DemoAnalysisApiIntegrationTests {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @MockitoBean Clock clock;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @BeforeEach void setup() {
        now.set(Instant.parse("2026-10-31T00:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    @Test void keepsRunningForFiveSecondsThenReturnsStaticResultWithMatchingId() throws Exception {
        HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(base()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"articleUrl\":\"https://news.example.com/demo\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode acceptedBody = json.readTree(accepted.body());
        String id = acceptedBody.path("analysisId").asString();
        assertEquals("QUEUED", acceptedBody.path("status").asString());

        HttpResponse<String> running = get(id);
        assertEquals(200, running.statusCode(), running.body());
        JsonNode runningBody = json.readTree(running.body());
        assertEquals("RUNNING", runningBody.path("status").asString());
        assertFalse(runningBody.path("activeStages").isEmpty());
        assertError(getPath(id + "/result"), 409, "ANALYSIS_NOT_READY");

        now.set(now.get().plusSeconds(5));
        JsonNode completedStatus = json.readTree(get(id).body());
        assertEquals("COMPLETED", completedStatus.path("status").asString());
        assertTrue(completedStatus.path("activeStages").isEmpty());

        HttpResponse<String> resultResponse = getPath(id + "/result");
        assertEquals(200, resultResponse.statusCode(), resultResponse.body());
        assertEquals("no-store", resultResponse.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode result = json.readTree(resultResponse.body());
        assertEquals(id, result.path("analysisId").asString());
        assertEquals("COMPLETED", result.path("status").asString());
        assertEquals(Set.of("analysisId", "status", "article", "summary", "crossReports", "sourceAssessments", "evidence", "warnings"), fields(result));
        assertEquals("서울 아파트값 0.18%↑…상승폭 둔화속 42주 연속 올라", result.path("article").path("title").asString());
        assertEquals(4, result.path("crossReports").size());
        assertEquals(2, result.path("sourceAssessments").size());
        assertEquals(12, result.path("evidence").size());
        assertTrue(result.path("warnings").isEmpty());
    }

    @Test void validatesDemoRequestLikeTheRealAnalysisApi() throws Exception {
        var missing = http.send(HttpRequest.newBuilder(base()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertError(missing, 400, "INVALID_URL");

        var malformed = http.send(HttpRequest.newBuilder(base()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"articleUrl\":123}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertError(malformed, 400, "INVALID_REQUEST");
    }

    private Set<String> fields(JsonNode object) {
        Set<String> values = new HashSet<>();
        object.propertyNames().forEach(values::add);
        return values;
    }

    private HttpResponse<String> get(String id) throws Exception {
        return getPath(id);
    }

    private HttpResponse<String> getPath(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("error").path("code").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private URI base() {
        return URI.create("http://localhost:" + port + "/api/v1/demo/analyses");
    }
}
