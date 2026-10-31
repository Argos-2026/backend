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

    @Test void publishesThreeDocumentedOperationsFromControllerInterface() throws Exception {
        JsonNode doc = document();
        assertEquals("ARGOS API", doc.path("info").path("title").asString());
        assertEquals("/", doc.path("servers").get(0).path("url").asString());
        JsonNode paths = doc.path("paths");
        assertEquals(Set.of("/api/v1/analyses", "/api/v1/analyses/{analysisId}", "/api/v1/analyses/{analysisId}/result"), names(paths));
        assertOperation(paths.path("/api/v1/analyses").path("post"), "createAnalysis", "뉴스 분석 요청", Set.of("202", "400", "415", "422", "429", "500", "503"));
        assertOperation(paths.path("/api/v1/analyses/{analysisId}").path("get"), "getAnalysisStatus", "분석 상태 조회", Set.of("200", "400", "404", "500"));
        assertOperation(paths.path("/api/v1/analyses/{analysisId}/result").path("get"), "getAnalysisResult", "분석 결과 조회", Set.of("200", "400", "404", "409", "500"));
        for (String path : List.of("/api/v1/analyses/{analysisId}", "/api/v1/analyses/{analysisId}/result")) {
            JsonNode parameters = paths.path(path).path("get").path("parameters");
            assertEquals(1, parameters.size());
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
            .path("responses").path("200").path("content").path("application/json").path("schema"), doc);
        assertEquals(Set.of("analysisId", "status", "analysisOutcome", "article", "summary", "claims", "evidence", "articleSources", "warnings"), names(result.path("properties")));
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

    @Test void swaggerUiAndItsConfigurationAreAvailableWithoutExternalApiKeys() throws Exception {
        var ui = get("/swagger-ui.html");
        assertEquals(200, ui.statusCode());
        assertTrue(ui.body().contains("Swagger UI"));
        var config = get("/v3/api-docs/swagger-config");
        assertEquals(200, config.statusCode());
        assertEquals("/v3/api-docs", json.readTree(config.body()).path("url").asString());
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
