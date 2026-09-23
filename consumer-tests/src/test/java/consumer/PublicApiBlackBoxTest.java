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
import java.util.concurrent.atomic.AtomicInteger;
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
  private final AtomicInteger requestCount = new AtomicInteger();
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
    JevEvaluator.Evaluation<Jev.NoulAnswer> result =
        evaluator()
            .evaluateWithMetadata(
                "clouds",
                Jev.noul("Is rain likely?")
                    .describe(true, "rain is likely")
                    .describe(false, "rain is unlikely")
                    .threshold(0.73));
    assertJson("noul-request.json", request.get());
    JsonNode goldenRequest = JSON.readTree(request.get());
    assertEquals("clouds", goldenRequest.get("state").textValue());
    assertEquals(
        "Is rain likely?",
        goldenRequest.get("questions").get("question").get("instructions").textValue());
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

    response.set(
        "{\"model\":\"fixture-provider\",\"answers\":{\"question\":{\"type\":\"noul\",\"noul\":0.5}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    JevEvaluator evaluator = evaluator();
    int before = requestCount.get();
    assertTrue(evaluator.test("state", Jev.noul("default boundary")));
    assertEquals(before + 1, requestCount.get());
    assertFalse(evaluator.test("state", Jev.noul("custom threshold").threshold(.51)));
    assertEquals(before + 2, requestCount.get());
    response.set(
        "{\"model\":\"fixture-provider\",\"answers\":{\"question\":{\"type\":\"noul\",\"noul\":0.49}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    assertFalse(evaluator.test("state", Jev.noul("below default threshold")));
    assertEquals(before + 3, requestCount.get());
    assertEquals(
        .8,
        Jev.noul("preserve threshold")
            .threshold(.8)
            .describe(true, "yes")
            .describe(false, "no")
            .threshold());
  }

  @Test
  void choiceGoldenRoutesAcceptedAndRejectedAnswers() throws Exception {
    response.set(fixture("choice-response.json"));
    Jev.ChoiceQuestion<Route> acceptedQuestion =
        Jev.choice(Route.class, "Route the request").minConfidence(.8).minProbability(.75);
    Jev.ChoiceAnswer<Route> accepted =
        evaluator().evaluate("customer asks about a parcel", acceptedQuestion);
    assertJson("choice-request.json", request.get());
    assertEquals(Route.DELIVERY, accepted.acceptedValue().orElseThrow());
    assertTrue(accepted.is(Route.DELIVERY));
    assertFalse(accepted.is(Route.BILLING));
    assertThrows(NullPointerException.class, () -> accepted.is(null));
    assertEquals(
        Map.of(Route.BILLING, .11, Route.DELIVERY, .78, Route.OTHER, .11),
        accepted.probabilities());
    response.set(fixture("choice-response.json"));
    assertTrue(
        evaluator()
            .evaluate("customer asks about a parcel", acceptedQuestion.minConfidence(.82))
            .acceptedValue()
            .isEmpty());
    response.set(fixture("choice-response.json"));
    Jev.ChoiceAnswer<Route> probabilityRejected =
        evaluator()
            .evaluate(
                "customer asks about a parcel",
                acceptedQuestion.minConfidence(0).minProbability(.79));
    assertEquals(Route.DELIVERY, probabilityRejected.value());
    assertFalse(probabilityRejected.is(Route.DELIVERY));
    assertTrue(probabilityRejected.acceptedValue().isEmpty());
  }

  @Test
  void bothScoreFormsPreserveFractionOrderAndRejection() throws Exception {
    response.set(fixture("score-response.json"));
    Jev.EnumScoreAnswer<Quality> typed =
        evaluator()
            .evaluate(
                "short but correct", Jev.score(Quality.class, "Rate quality").minConfidence(.7));
    assertJson("score-request.json", request.get());
    assertEquals(1.4, typed.value());
    assertEquals(Quality.ACCEPTABLE, typed.nearestLevel());
    assertTrue(typed.acceptedValue().isEmpty());
    assertTrue(typed.acceptedLevel().isEmpty());
    assertEquals(
        Map.of(Quality.POOR, .1, Quality.ACCEPTABLE, .4, Quality.EXCELLENT, .5),
        typed.probabilities());
    response.set(fixture("score-response.json"));
    Jev.ScoreAnswer plain =
        evaluator()
            .evaluate(
                "short but correct",
                Jev.score("Rate quality")
                    .level("poor")
                    .level("acceptable")
                    .level("excellent")
                    .build());
    assertEquals(1.4, plain.acceptedValue().orElseThrow());
    assertEquals(java.util.List.of(.1, .4, .5), plain.probabilities());

    response.set(
        "{\"model\":\"fixture-provider\",\"answers\":{\"question\":{\"type\":\"score\",\"score\":1.5,\"probabilities\":{\"0\":0.1,\"1\":0.4,\"2\":0.5},\"confidence\":0.7}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    Jev.EnumScoreAnswer<Quality> midpoint =
        evaluator()
            .evaluate(
                "short but correct", Jev.score(Quality.class, "Rate quality").minConfidence(.7));
    assertEquals(Quality.EXCELLENT, midpoint.acceptedLevel().orElseThrow());
  }

  @Test
  void malformedAndHttpFailuresAreTypedAndSanitized() throws Exception {
    response.set("{\"private\":\"SENTINEL\"}");
    JevEvaluationException malformed =
        assertThrows(
            JevEvaluationException.class,
            () -> evaluator().evaluate("SECRET_STATE", Jev.noul("x")));
    assertFalse(malformed.getMessage().contains("SENTINEL"));
    assertFalse(malformed.getMessage().contains("SECRET_STATE"));
    status = 429;
    response.set("provider secret SENTINEL");
    JevEvaluationException http =
        assertThrows(
            JevEvaluationException.class,
            () -> evaluator().evaluate("SECRET_STATE", Jev.noul("x")));
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
            JevEvaluationException.class, () -> evaluator().evaluate("SECRET", Jev.noul("x")));
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
            JevEvaluationException.class, () -> evaluator().evaluate("SECRET", Jev.noul("x")));
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
              () -> evaluator().evaluate("state", Jev.choice(Route.class, "x")));
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
    base.model("first").build().evaluate("state", Jev.noul("x"));
    assertEquals("first", JSON.readTree(request.get()).get("model").textValue());
    base.model("second").build().evaluate("state", Jev.noul("x"));
    assertEquals("second", JSON.readTree(request.get()).get("model").textValue());
    base.build().evaluate("state", Jev.noul("x"));
    assertEquals("jev-latest", JSON.readTree(request.get()).get("model").textValue());

    server.stop(0);
    assertThrows(JevEvaluationException.class, () -> base.build().evaluate("state", Jev.noul("x")));
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/systemone", this::handle);
    server.start();
    delayMillis = 150;
    JevEvaluator timed =
        JevEvaluator.builder("key").baseUri(baseUri()).timeout(Duration.ofMillis(20)).build();
    assertThrows(JevEvaluationException.class, () -> timed.evaluate("state", Jev.noul("x")));
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
    requestCount.incrementAndGet();
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
