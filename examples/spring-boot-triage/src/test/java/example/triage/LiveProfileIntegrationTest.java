package example.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("live")
@ActiveProfiles("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveProfileIntegrationTest {
  private static final HttpServer PROVIDER = startProvider();
  private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
  private static final AtomicInteger PROVIDER_STATUS = new AtomicInteger(200);
  private static final AtomicReference<Double> CHOICE_CONFIDENCE = new AtomicReference<>(0.9);

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry properties) {
    properties.add("jev.base-uri", () -> "http://localhost:" + PROVIDER.getAddress().getPort());
    properties.add("jev.api-key", () -> "fake-live-test-key");
  }

  @AfterAll
  static void stopProvider() {
    PROVIDER.stop(0);
  }

  @BeforeEach
  void resetProvider() {
    REQUESTS.clear();
    PROVIDER_STATUS.set(200);
    CHOICE_CONFIDENCE.set(0.9);
  }

  @Test
  void starterEvaluatorAndRestEndpointUseTheConfiguredProvider() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/triage"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"live customer message\"}"))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(
        200, response.statusCode(), response.body() + "; provider calls=" + REQUESTS.size());
    tools.jackson.databind.JsonNode parsed =
        new tools.jackson.databind.ObjectMapper().readTree(response.body());
    assertEquals("DELIVERY", parsed.get("selectedCategory").textValue());
    assertEquals("DELIVERY_SUPPORT", parsed.get("queue").textValue());
    assertEquals("live-openrouter", parsed.get("answerSource").textValue());
    assertEquals(3, REQUESTS.size());
    assertTrue(
        REQUESTS.stream().allMatch(body -> body.contains("\"state\":\"live customer message\"")));
    assertTrue(REQUESTS.stream().anyMatch(body -> body.contains("\"type\":\"choice\"")));
    assertTrue(REQUESTS.stream().anyMatch(body -> body.contains("\"type\":\"noul\"")));
    assertTrue(REQUESTS.stream().anyMatch(body -> body.contains("\"type\":\"score\"")));
  }

  @Test
  void sanitizesRealProviderHttpFailures() throws Exception {
    PROVIDER_STATUS.set(500);
    HttpResponse<String> response = post("private customer message");

    assertEquals(503, response.statusCode());
    tools.jackson.databind.JsonNode body =
        new tools.jackson.databind.ObjectMapper().readTree(response.body());
    assertEquals("EVALUATION_UNAVAILABLE", body.get("code").textValue());
    assertEquals("triage evaluation is temporarily unavailable", body.get("message").textValue());
    assertTrue(!response.body().contains("private customer message"));
  }

  @Test
  void confidenceBoundaryAndUncertaintyAreVisibleThroughLiveHttp() throws Exception {
    CHOICE_CONFIDENCE.set(0.70);
    assertEquals(
        "ROUTE",
        new tools.jackson.databind.ObjectMapper()
            .readTree(post("boundary").body())
            .get("decision")
            .textValue());
    CHOICE_CONFIDENCE.set(Math.nextDown(0.70));
    assertEquals(
        "MANUAL_REVIEW_LOW_CONFIDENCE",
        new tools.jackson.databind.ObjectMapper()
            .readTree(post("uncertain").body())
            .get("decision")
            .textValue());
  }

  private HttpResponse<String> post(String message) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/triage"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"" + message + "\"}"))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpServer startProvider() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext("/v1/systemone", LiveProfileIntegrationTest::respond);
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void respond(HttpExchange exchange) throws IOException {
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    REQUESTS.add(request);
    if (PROVIDER_STATUS.get() != 200) {
      byte[] failure = "provider private details".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(PROVIDER_STATUS.get(), failure.length);
      exchange.getResponseBody().write(failure);
      exchange.close();
      return;
    }
    String answer;
    if (request.contains("\"type\":\"choice\"")) {
      answer =
          "{\"type\":\"choice\",\"choice\":\"DELIVERY\",\"probabilities\":{\"BILLING\":0.05,\"DELIVERY\":0.9,\"OTHER\":0.05},\"confidence\":"
              + CHOICE_CONFIDENCE.get()
              + "}";
    } else if (request.contains("\"type\":\"noul\"")) {
      answer = "{\"type\":\"noul\",\"noul\":0.1}";
    } else {
      answer =
          "{\"type\":\"score\",\"score\":0.75,\"probabilities\":{\"0\":0.25,\"1\":0.75,\"2\":0.0},\"confidence\":0.9}";
    }
    byte[] response =
        ("{\"model\":\"fake-jev\",\"answers\":{\"question\":"
                + answer
                + "},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
