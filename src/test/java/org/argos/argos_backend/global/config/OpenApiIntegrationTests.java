package org.argos.argos_backend.global.config;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "argos.openai-api-key=", "argos.naver-client-id=", "argos.naver-client-secret=", "argos.brave-api-key="
})
class OpenApiIntegrationTests {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Test void publishesDocumentedOperationsFromControllerInterface() throws Exception {
        JsonNode doc = document();
        assertEquals("ARGOS API", doc.path("info").path("title").asString());
        assertEquals("/", doc.path("servers").get(0).path("url").asString());
        JsonNode paths = doc.path("paths");
        assertEquals(Set.of("/api/v1/analyses", "/api/v1/analyses/{analysisId}", "/api/v1/analyses/{analysisId}/result", "/api/v1/analyses/{analysisId}/cross-report/trace"), names(paths));
        assertOperation(paths.path("/api/v1/analyses").path("post"), "createAnalysis", "뉴스 분석 요청", Set.of("202", "400", "415", "422", "429", "500", "503"));
        assertOperation(paths.path("/api/v1/analyses/{analysisId}").path("get"), "getAnalysisStatus", "분석 상태 조회", Set.of("200", "400", "404", "500"));
        assertOperation(paths.path("/api/v1/analyses/{analysisId}/result").path("get"), "getAnalysisResult", "분석 결과 조회", Set.of("200", "400", "404", "409", "500"));
        assertOperation(paths.path("/api/v1/analyses/{analysisId}/cross-report/trace").path("get"), "getCrossReportTrace", "교차검증 Trace 조회", Set.of("200", "400", "404", "500"));
        for (String path : List.of("/api/v1/analyses/{analysisId}", "/api/v1/analyses/{analysisId}/result", "/api/v1/analyses/{analysisId}/cross-report/trace")) {
            JsonNode parameters = paths.path(path).path("get").path("parameters");
            assertEquals(path.endsWith("/result") ? 2 : 1, parameters.size());
            assertEquals("analysisId", parameters.get(0).path("name").asString());
            assertEquals("path", parameters.get(0).path("in").asString());
            assertTrue(parameters.get(0).path("required").asBoolean());
            assertEquals("uuid", parameters.get(0).path("schema").path("format").asString());
        }
    }

    @Test void documentsActualRequestAndTypedErrorEnvelopeInsteadOfJsonNodeOrMap() throws Exception {
        JsonNode doc = document();
        JsonNode post = doc.path("paths").path("/api/v1/analyses").path("post");
        JsonNode body = post.path("requestBody");
        assertTrue(body.path("required").asBoolean());
        JsonNode requestSchema = resolve(body.path("content").path("application/json").path("schema"), doc);
        assertEquals(Set.of("articleUrl"), names(requestSchema.path("properties")));
        assertEquals("uri", requestSchema.path("properties").path("articleUrl").path("format").asString());
        assertEquals("articleUrl", requestSchema.path("required").get(0).asString());
        JsonNode error = resolve(post.path("responses").path("400").path("content").path("application/json").path("schema"), doc);
        assertEquals(Set.of("error"), names(error.path("properties")));
        JsonNode details = resolve(error.path("properties").path("error"), doc);
        assertEquals(Set.of("code", "message"), names(details.path("properties")));
        JsonNode errorMedia = post.path("responses").path("400").path("content").path("application/json");
        assertTrue(errorMedia.has("example") || !errorMedia.path("examples").isEmpty());
    }

    @Test void includesResultAndStatusSchemasWithoutInternalJobOrCredentialModels() throws Exception {
        JsonNode doc = document();
        JsonNode result = resolve(doc.path("paths").path("/api/v1/analyses/{analysisId}/result").path("get")
            .path("responses").path("200").path("content").path("application/json").path("schema").path("oneOf").get(0), doc);
        assertEquals(Set.of("analysisId", "status", "article", "summary", "crossReports", "sourceAssessments", "evidence", "warnings"), names(result.path("properties")));
        JsonNode article = resolve(result.path("properties").path("article"), doc);
        assertFalse(article.path("properties").has("body"));
        JsonNode schemas = doc.path("components").path("schemas");
        assertFalse(schemas.has("Snapshot"));
        assertFalse(schemas.has("AnalysisJob"));
        assertFalse(schemas.has("ArgosProperties"));
        JsonNode status = resolve(doc.path("paths").path("/api/v1/analyses/{analysisId}").path("get")
            .path("responses").path("200").path("content").path("application/json").path("schema"), doc);
        assertEquals(Set.of("analysisId", "status", "activeStages", "expiresAt", "warnings", "error"), names(status.path("properties")));
    }

    @Test void documentsSectionSpecificSchemasExamplesAndTraceWithoutInternalModels() throws Exception {
        JsonNode doc = document();
        JsonNode operation = doc.path("paths").path("/api/v1/analyses/{analysisId}/result").path("get");
        JsonNode section = operation.path("parameters").get(1);
        assertEquals("section", section.path("name").asString());
        assertEquals("all", section.path("schema").path("default").asString());
        assertEquals(List.of("all", "cross-report", "source"),
            json.readValue(section.path("schema").path("enum").toString(), List.class));
        JsonNode media = operation.path("responses").path("200").path("content").path("application/json");
        JsonNode oneOf = media.path("schema").path("oneOf");
        assertEquals(3, oneOf.size());
        assertEquals("#/components/schemas/Result", oneOf.get(0).path("$ref").asString());
        assertEquals("#/components/schemas/CrossReportResultResponse", oneOf.get(1).path("$ref").asString());
        assertEquals("#/components/schemas/SourceResultResponse", oneOf.get(2).path("$ref").asString());
        int index = 0;
        for (String exampleName : List.of("all (기본값)", "cross-report", "source")) {
            JsonNode value = media.path("examples").path(exampleName).path("value");
            assertEquals(names(resolve(oneOf.get(index++), doc).path("properties")), names(value));
        }
        JsonNode traceMedia = doc.path("paths").path("/api/v1/analyses/{analysisId}/cross-report/trace").path("get")
            .path("responses").path("200").path("content").path("application/json");
        JsonNode traceSchema = resolve(traceMedia.path("schema"), doc);
        assertEquals(Set.of("analysisId", "status", "claims", "error"), names(traceSchema.path("properties")));
        JsonNode traceExample = traceMedia.path("examples").path("검색부터 최종 비교까지").path("value");
        assertEquals(names(traceSchema.path("properties")), names(traceExample));
        JsonNode candidate = resolve(doc.path("components").path("schemas").path("CandidateTrace"), doc);
        assertFalse(candidate.path("properties").has("body"));
        assertFalse(candidate.path("properties").has("selectionReason"));
        assertEquals(4, traceExample.path("claims").get(0).path("searches").size());
    }

    @Test void swaggerUiAndItsConfigurationAreAvailableWithoutExternalApiKeys() throws Exception {
        var ui = get("/swagger-ui.html");
        assertEquals(200, ui.statusCode());
        assertTrue(ui.body().contains("Swagger UI"));
        var config = get("/v3/api-docs/swagger-config");
        assertEquals(200, config.statusCode());
        assertEquals("/v3/api-docs", json.readTree(config.body()).path("url").asString());
    }

    @Test void documentsAdditivePerspectiveTraceWithoutChangingFinalVerdictEnums() throws Exception {
        JsonNode doc = document();
        JsonNode candidate = doc.path("components").path("schemas").path("CandidateTrace").path("properties");
        assertTrue(names(candidate).containsAll(Set.of("llmSelected", "comparison", "claimAnalysis", "document",
            "conflictResolution", "usedQuotes", "verificationSelection", "searchDescription", "searchDateHint",
            "prefetchPriority", "matchResolution")));
        JsonNode claims = doc.path("components").path("schemas").path("ClaimTrace").path("properties");
        assertTrue(names(claims).containsAll(Set.of("issue", "neutralQuery", "challengeQuery", "enrichmentIssues", "temporalContext")));
        JsonNode verdict = doc.path("components").path("schemas").path("CrossReport").path("properties").path("verdict");
        assertEquals(List.of("MATCHED", "CONTRADICTED", "MIXED", "UNCONFIRMED"), json.readValue(verdict.path("enum").toString(), List.class));
        JsonNode examples = doc.path("paths").path("/api/v1/analyses/{analysisId}/cross-report/trace").path("get")
            .path("responses").path("200").path("content").path("application/json").path("examples");
        assertTrue(examples.has("관점 후보와 충돌 검증"));
        assertEquals(6, examples.path("관점 후보와 충돌 검증").path("value").path("claims").get(0).path("searches").size());
    }

    private void assertOperation(JsonNode operation, String id, String summary, Set<String> codes) {
        assertEquals(id, operation.path("operationId").asString());
        assertEquals(summary, operation.path("summary").asString());
        assertFalse(operation.path("description").asString("").isBlank());
        assertEquals("뉴스 분석", operation.path("tags").get(0).asString());
        assertEquals(codes, names(operation.path("responses")));
    }
    private JsonNode document() throws Exception {
        var response = get("/v3/api-docs");
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    private JsonNode resolve(JsonNode schema, JsonNode document) {
        while (schema.has("$ref")) schema = document.path("components").path("schemas").path(schema.path("$ref").asString().substring("#/components/schemas/".length()));
        return schema;
    }
    private Set<String> names(JsonNode object) {
        Set<String> values = new HashSet<>(); object.propertyNames().forEach(values::add); return values;
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
