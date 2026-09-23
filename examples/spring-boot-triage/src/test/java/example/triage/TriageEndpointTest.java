package example.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("synthetic")
class TriageEndpointTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @LocalServerPort private int port;

  @Test
  void routesTheThreeDocumentedFixturesOverHttp() throws Exception {
    assertRoute("I was charged twice for my groceries.", "BILLING", "BILLING_SUPPORT");
    assertRoute("My delivery is late and has not arrived.", "DELIVERY", "DELIVERY_SUPPORT");
    assertRoute("How do I update my loyalty card name?", "OTHER", "GENERAL_SUPPORT");
  }

  @Test
  void unfamiliarTextUsesExplicitManualReview() throws Exception {
    JsonNode body = post("Unlisted fixture", 200);
    assertEquals("OTHER", body.get("selectedCategory").textValue());
    assertEquals("MANUAL_REVIEW", body.get("queue").textValue());
    assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", body.get("decision").textValue());
    assertEquals(0.41, body.get("confidence").doubleValue());
  }

  @Test
  void enforcesInputBoundaries() throws Exception {
    assertEquals("INVALID_INPUT", post("  ", 400).get("code").textValue());
    post("x".repeat(1000), 200);
    assertEquals("INVALID_INPUT", post("x".repeat(1001), 400).get("code").textValue());
  }

  private void assertRoute(String message, String category, String queue) throws Exception {
    JsonNode body = post(message, 200);
    assertEquals(category, body.get("selectedCategory").textValue());
    assertEquals(queue, body.get("queue").textValue());
    assertEquals("ROUTE", body.get("decision").textValue());
    assertEquals("synthetic-offline-fixture", body.get("answerSource").textValue());
    assertEquals(3, body.get("categoryProbabilities").size());
  }

  private JsonNode post(String message, int expectedStatus) throws Exception {
    String requestBody = JSON.writeValueAsString(Map.of("message", message));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/triage"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
