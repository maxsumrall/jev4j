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
import org.junit.jupiter.api.AfterAll;
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
    assertTrue(response.body().contains("\"selectedCategory\":\"DELIVERY\""));
    assertTrue(response.body().contains("\"queue\":\"DELIVERY_SUPPORT\""));
    assertTrue(response.body().contains("\"answerSource\":\"live-openrouter\""));
    assertEquals(3, REQUESTS.size());
    assertTrue(
        REQUESTS.stream().allMatch(body -> body.contains("\"state\":\"live customer message\"")));
    assertTrue(REQUESTS.stream().anyMatch(body -> body.contains("\"type\":\"choice\"")));
    assertTrue(REQUESTS.stream().anyMatch(body -> body.contains("\"type\":\"noul\"")));
    assertTrue(REQUESTS.stream().anyMatch(body -> body.contains("\"type\":\"score\"")));
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
    String answer;
    if (request.contains("\"type\":\"choice\"")) {
      answer =
          "{\"type\":\"choice\",\"choice\":\"DELIVERY\",\"probabilities\":{\"BILLING\":0.05,\"DELIVERY\":0.9,\"OTHER\":0.05},\"confidence\":0.9}";
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
