package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluationException;
import io.github.maxsumrall.jev4j.JevEvaluator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class PublicApiBlackBoxTest {
  enum Route implements Jev.Described {
    BILLING("charges and refunds"),
    DELIVERY("shipping and parcels"),
    OTHER("everything else");
    private final String description;

    Route(String description) {
      this.description = description;
    }

    public String description() {
      return description;
    }
  }

  enum Quality implements Jev.ScoreLevel {
    POOR("poor"),
    ACCEPTABLE("acceptable"),
    EXCELLENT("excellent");
    private final String description;

    Quality(String description) {
      this.description = description;
    }

    public String description() {
      return description;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<String> request = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private HttpServer server;
  private int status;
  private long delayMillis;

  @BeforeEach
  void start() throws IOException {
    status = 200;
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/systemone", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void noulGoldenRequestOmitsThresholdAndPreservesMetadata() throws Exception {
    response.set(fixture("noul-response.json"));
    JevEvaluator.Evaluation<Jev.ThresholdNoulAnswer> result =
        evaluator()
            .evaluateWithMetadata(
                Jev.noul("Is rain likely?")
                    .describe(true, "rain is likely")
                    .describe(false, "rain is unlikely")
                    .threshold(0.73),
                "clouds");
    assertJson("noul-request.json", request.get());
    assertFalse(request.get().contains("threshold"));
    assertEquals("Bearer safe-dummy-key", authorization.get());
    assertEquals("POST", method.get());
    assertEquals("application/json", contentType.get());
    assertEquals(0.73, result.answer().probabilityTrue());
    assertTrue(result.answer().isTrue());
    assertEquals("fixture-1", result.id().orElseThrow());
    assertEquals("local", result.provider().orElseThrow());
    assertEquals(11, result.usage().inputTokens());
    assertEquals(0.0001, result.usage().cost().orElseThrow());

    response.set(fixture("noul-response.json"));
    assertEquals(0.73, evaluator().evaluate(Jev.noul("plain noul"), "state").probabilityTrue());
  }

  @Test
  void choiceGoldenRoutesAcceptedAndRejectedAnswers() throws Exception {
    response.set(fixture("choice-response.json"));
    Jev.ChoiceQuestion<Route> acceptedQuestion =
        Jev.choice(Route.class, "Route the request").minConfidence(.8).minProbability(.75);
    Jev.ChoiceAnswer<Route> accepted =
        evaluator().evaluate(acceptedQuestion, "customer asks about a parcel");
    assertJson("choice-request.json", request.get());
    assertEquals(Route.DELIVERY, accepted.acceptedValue().orElseThrow());
    assertEquals(
        Map.of(Route.BILLING, .11, Route.DELIVERY, .78, Route.OTHER, .11),
        accepted.probabilities());
    response.set(fixture("choice-response.json"));
    assertTrue(
        evaluator()
            .evaluate(acceptedQuestion.minConfidence(.82), "customer asks about a parcel")
            .acceptedValue()
            .isEmpty());
    response.set(fixture("choice-response.json"));
    Jev.ChoiceAnswer<Route> probabilityRejected =
        evaluator()
            .evaluate(
                acceptedQuestion.minConfidence(0).minProbability(.79),
                "customer asks about a parcel");
    assertEquals(Route.DELIVERY, probabilityRejected.value());
    assertTrue(probabilityRejected.acceptedValue().isEmpty());
  }

  @Test
  void bothScoreFormsPreserveFractionOrderAndRejection() throws Exception {
    response.set(fixture("score-response.json"));
    Jev.EnumScoreAnswer<Quality> typed =
        evaluator()
            .evaluate(
                Jev.score(Quality.class, "Rate quality").minConfidence(.7), "short but correct");
    assertJson("score-request.json", request.get());
    assertEquals(1.4, typed.value());
    assertEquals(Quality.ACCEPTABLE, typed.nearestLevel());
    assertTrue(typed.acceptedValue().isEmpty());
    assertEquals(
        Map.of(Quality.POOR, .1, Quality.ACCEPTABLE, .4, Quality.EXCELLENT, .5),
        typed.probabilities());
    response.set(fixture("score-response.json"));
    Jev.ScoreAnswer plain =
        evaluator()
            .evaluate(
                Jev.score("Rate quality")
                    .level("poor")
                    .level("acceptable")
                    .level("excellent")
                    .build(),
                "short but correct");
    assertEquals(1.4, plain.acceptedValue().orElseThrow());
    assertEquals(java.util.List.of(.1, .4, .5), plain.probabilities());
  }

  @Test
  void malformedAndHttpFailuresAreTypedAndSanitized() throws Exception {
    response.set("{\"private\":\"SENTINEL\"}");
    JevEvaluationException malformed =
        assertThrows(
            JevEvaluationException.class,
            () -> evaluator().evaluate(Jev.noul("x"), "SECRET_STATE"));
    assertFalse(malformed.getMessage().contains("SENTINEL"));
    assertFalse(malformed.getMessage().contains("SECRET_STATE"));
    status = 429;
    response.set("provider secret SENTINEL");
    JevEvaluationException http =
        assertThrows(
            JevEvaluationException.class,
            () -> evaluator().evaluate(Jev.noul("x"), "SECRET_STATE"));
    assertEquals(429, http.httpStatusCode().orElseThrow());
    assertFalse(http.getMessage().contains("SENTINEL"));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 429, 500})
  void httpFailuresExposeOnlyStructuredStatus(int code) {
    status = code;
    response.set("private SENTINEL");
    JevEvaluationException failure =
        assertThrows(
            JevEvaluationException.class, () -> evaluator().evaluate(Jev.noul("x"), "SECRET"));
    assertEquals(code, failure.httpStatusCode().orElseThrow());
    assertFalse(failure.getMessage().contains("SENTINEL"));
    assertFalse(failure.getMessage().contains("SECRET"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-json",
        "{\"model\":\"m\",\"answers\":{\"question\":{\"type\":\"wrong\",\"noul\":0.5}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
        "{\"model\":\"m\",\"answers\":{\"question\":{\"type\":\"noul\"}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
        "{\"model\":\"m\",\"answers\":{\"question\":{\"type\":\"noul\",\"noul\":null}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
        "{\"model\":\"m\",\"answers\":{\"question\":{\"type\":\"noul\",\"noul\":1e400}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"
      })
  void invalidNoulResponsesAreSanitizedAndHaveNoStatus(String body) {
    response.set(body);
    JevEvaluationException failure =
        assertThrows(
            JevEvaluationException.class, () -> evaluator().evaluate(Jev.noul("x"), "SECRET"));
    assertTrue(failure.httpStatusCode().isEmpty());
    for (Throwable current = failure; current != null; current = current.getCause()) {
      assertFalse(Objects.toString(current.getMessage(), "").contains("SECRET"));
    }
  }

  @Test
  void invalidChoiceResponsesRejectUnknownIncompleteAndNonunitDistributions() throws Exception {
    JsonNode root = JSON.readTree(fixture("choice-response.json"));
    for (String answer :
        List.of(
            "{\"type\":\"choice\",\"choice\":\"UNKNOWN\",\"probabilities\":{\"BILLING\":0.11,\"DELIVERY\":0.78,\"OTHER\":0.11},\"confidence\":0.81}",
            "{\"type\":\"choice\",\"choice\":\"DELIVERY\",\"probabilities\":{\"BILLING\":0.11,\"DELIVERY\":0.89},\"confidence\":0.81}",
            "{\"type\":\"choice\",\"choice\":\"DELIVERY\",\"probabilities\":{\"BILLING\":0.1,\"DELIVERY\":0.7,\"OTHER\":0.1},\"confidence\":0.81}")) {
      ((tools.jackson.databind.node.ObjectNode) root.get("answers"))
          .set("question", JSON.readTree(answer));
      response.set(JSON.writeValueAsString(root));
      JevEvaluationException failure =
          assertThrows(
              JevEvaluationException.class,
              () -> evaluator().evaluate(Jev.choice(Route.class, "x"), "state"));
      assertTrue(failure.httpStatusCode().isEmpty());
    }
  }

  @Test
  void unsafeKeysBuilderBranchesNetworkAndTimeoutAreBounded() throws Exception {
    for (String key : List.of("key\rSENTINEL", "key\nSENTINEL", "key\u0000SENTINEL")) {
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> JevEvaluator.builder(key).build());
      assertFalse(failure.getMessage().contains("SENTINEL"));
      assertNull(failure.getCause(), "credential validation must not retain a leaking cause");
    }
    response.set(fixture("noul-response.json"));
    JevEvaluator.Builder base =
        JevEvaluator.builder("safe-dummy-key").baseUri(baseUri()).timeout(Duration.ofSeconds(1));
    base.model("first").build().evaluate(Jev.noul("x"), "state");
    assertEquals("first", JSON.readTree(request.get()).get("model").textValue());
    base.model("second").build().evaluate(Jev.noul("x"), "state");
    assertEquals("second", JSON.readTree(request.get()).get("model").textValue());
    base.build().evaluate(Jev.noul("x"), "state");
    assertEquals("jev-latest", JSON.readTree(request.get()).get("model").textValue());

    server.stop(0);
    assertThrows(JevEvaluationException.class, () -> base.build().evaluate(Jev.noul("x"), "state"));
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/systemone", this::handle);
    server.start();
    delayMillis = 150;
    JevEvaluator timed =
        JevEvaluator.builder("key").baseUri(baseUri()).timeout(Duration.ofMillis(20)).build();
    assertThrows(JevEvaluationException.class, () -> timed.evaluate(Jev.noul("x"), "state"));
  }

  private JevEvaluator evaluator() {
    return JevEvaluator.builder("safe-dummy-key")
        .baseUri(URI.create("http://localhost:" + server.getAddress().getPort()))
        .model("fixture-model")
        .build();
  }

  private URI baseUri() {
    return URI.create("http://localhost:" + server.getAddress().getPort());
  }

  private void handle(HttpExchange exchange) throws IOException {
    method.set(exchange.getRequestMethod());
    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    if (delayMillis > 0) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String fixture(String name) throws IOException {
    return Files.readString(Path.of("src/test/resources/fixtures", name));
  }

  private static void assertJson(String expected, String actual) throws IOException {
    JsonNode expectedNode = JSON.readTree(fixture(expected));
    assertEquals(expectedNode, JSON.readTree(actual));
  }
}
