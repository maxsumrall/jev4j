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

  enum Level {
    LOW,
    MEDIUM,
    HIGH
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<String> request = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private final AtomicReference<String> requestId = new AtomicReference<>();
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
    assertTrue(result.requestId().isEmpty());
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
    assertEquals(Quality.EXCELLENT, typed.mostLikelyLevel());
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
  void mostLikelyLevelDiffersFromNearestWithoutChangingAcceptance() {
    response.set(
        """
        {"model":"m","answers":{"question":{"type":"score","score":0.95,
        "probabilities":{"0":0.45,"1":0.15,"2":0.40},"confidence":0.7}},
        "usage":{"input_tokens":1,"output_tokens":1}}
        """);
    Jev.EnumScoreQuestion<Level> question = Jev.score(Level.class, "Rate level").minConfidence(.7);
    Jev.EnumScoreAnswer<Level> accepted = evaluator().evaluate("state", question);
    Level mostLikely = accepted.mostLikelyLevel();
    assertEquals(Level.LOW, mostLikely);
    assertEquals(.95, accepted.value());
    assertEquals(Level.MEDIUM, accepted.nearestLevel());
    assertEquals(Level.MEDIUM, accepted.acceptedLevel().orElseThrow());
    assertEquals(.95, accepted.acceptedValue().orElseThrow());
    assertEquals(
        Map.of(Level.LOW, .45, Level.MEDIUM, .15, Level.HIGH, .40), accepted.probabilities());

    Jev.EnumScoreAnswer<Level> rejected =
        evaluator().evaluate("state", question.minConfidence(.71));
    assertFalse(rejected.meetsThresholds());
    assertTrue(rejected.acceptedLevel().isEmpty());
    assertTrue(rejected.acceptedValue().isEmpty());
    assertEquals(.95, rejected.value());
    assertEquals(Level.MEDIUM, rejected.nearestLevel());
    assertEquals(Level.LOW, rejected.mostLikelyLevel());
    assertEquals(accepted.probabilities(), rejected.probabilities());
  }

  @Test
  void mostLikelyLevelBreaksNonadjacentTiesByEnumOrderNotJsonOrder() {
    response.set(
        """
        {"model":"m","answers":{"question":{"type":"score","score":1.0,
        "probabilities":{"2":0.45,"1":0.10,"0":0.45},"confidence":0.8}},
        "usage":{"input_tokens":1,"output_tokens":1}}
        """);
    Jev.EnumScoreAnswer<Level> answer =
        evaluator().evaluate("state", Jev.score(Level.class, "Rate level"));
    assertEquals(Level.LOW, answer.mostLikelyLevel());
    assertEquals(Level.MEDIUM, answer.nearestLevel());
    assertEquals(Level.MEDIUM, answer.acceptedLevel().orElseThrow());
  }

  @Test
  void multiQuestionCorrelatesByKeyMapsInOrderAndSharesMetadata() throws Exception {
    requestId.set("header-batch-3");
    response.set(
        "{\"id\":\"batch-1\",\"provider\":\"local\",\"model\":\"fixture-provider\",\"answers\":{"
            + "\"question3\":{\"type\":\"score\",\"score\":1.4,\"probabilities\":{\"0\":0.1,\"1\":0.4,\"2\":0.5},\"confidence\":0.8},"
            + "\"question1\":{\"type\":\"noul\",\"noul\":0.75},"
            + "\"question2\":{\"type\":\"choice\",\"choice\":\"DELIVERY\",\"probabilities\":{\"BILLING\":0.1,\"DELIVERY\":0.8,\"OTHER\":0.1},\"confidence\":0.9}},"
            + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}");
    int before = requestCount.get();
    JevEvaluator.Evaluation3<Jev.NoulAnswer, Jev.ChoiceAnswer<Route>, Jev.ScoreAnswer> result =
        evaluator()
            .evaluate(
                "state",
                Jev.noul("refund?"),
                Jev.choice(Route.class, "route"),
                Jev.score("quality").level("poor").level("ok").level("great").build());
    record Decision(boolean refund, Route route, double quality) {}
    Decision decision =
        result.map(
            (Jev.NoulAnswer refund, Jev.ChoiceAnswer<Route> route, Jev.ScoreAnswer quality) ->
                new Decision(refund.isTrue(), route.value(), quality.value()));
    assertEquals(new Decision(true, Route.DELIVERY, 1.4), decision);
    assertEquals("batch-1", result.id().orElseThrow());
    assertEquals("header-batch-3", result.requestId().orElseThrow());
    assertEquals(7, result.usage().inputTokens());
    assertEquals(before + 1, requestCount.get());
    assertEquals(
        List.of("question1", "question2", "question3"),
        JSON.readTree(request.get())
            .get("questions")
            .propertyStream()
            .map(Map.Entry::getKey)
            .toList());
  }

  @Test
  void multiQuestionSupportsArityEightAndRejectsMissingOrWrongAnswers() {
    String answer = "{\"type\":\"noul\",\"noul\":0.6}";
    StringBuilder answers = new StringBuilder();
    for (int i = 1; i <= 8; i++) {
      if (i > 1) answers.append(',');
      answers
          .append("\"question")
          .append(i)
          .append("\":{\"type\":\"noul\",\"noul\":0.")
          .append(i)
          .append('}');
    }
    response.set(
        "{\"model\":\"m\",\"answers\":{"
            + answers
            + "},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    JevEvaluator.Evaluation8<
            Jev.NoulAnswer,
            Jev.NoulAnswer,
            Jev.NoulAnswer,
            Jev.NoulAnswer,
            Jev.NoulAnswer,
            Jev.NoulAnswer,
            Jev.NoulAnswer,
            Jev.NoulAnswer>
        result =
            evaluator()
                .evaluate(
                    "state",
                    Jev.noul("1"),
                    Jev.noul("2"),
                    Jev.noul("3"),
                    Jev.noul("4"),
                    Jev.noul("5"),
                    Jev.noul("6"),
                    Jev.noul("7"),
                    Jev.noul("8"));
    assertEquals(
        List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8),
        result.map(
            (a, b, c, d, e, f, g, h) ->
                List.of(
                    a.probabilityTrue(),
                    b.probabilityTrue(),
                    c.probabilityTrue(),
                    d.probabilityTrue(),
                    e.probabilityTrue(),
                    f.probabilityTrue(),
                    g.probabilityTrue(),
                    h.probabilityTrue())));
    assertEquals(0.8, result.answer8().probabilityTrue());
    assertTrue(result.requestId().isEmpty());

    response.set(
        "{\"model\":\"m\",\"answers\":{\"question1\":"
            + answer
            + "},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    assertThrows(
        JevEvaluationException.class,
        () -> evaluator().evaluate("state", Jev.noul("1"), Jev.noul("2")));
    response.set(
        "{\"model\":\"m\",\"answers\":{\"question1\":"
            + answer
            + ",\"question2\":{\"type\":\"score\",\"score\":0}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    assertThrows(
        JevEvaluationException.class,
        () -> evaluator().evaluate("state", Jev.noul("1"), Jev.noul("2")));
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
    assertEquals(JevEvaluationException.FailureCategory.MALFORMED_RESPONSE, malformed.category());
    assertTrue(malformed.requestId().isEmpty());
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
    assertEquals(JevEvaluationException.FailureCategory.HTTP, failure.category());
    assertTrue(failure.requestId().isEmpty());
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
    requestId.set("malformed-header-id");
    response.set(body);
    JevEvaluationException failure =
        assertThrows(
            JevEvaluationException.class, () -> evaluator().evaluate("SECRET", Jev.noul("x")));
    assertTrue(failure.httpStatusCode().isEmpty());
    assertEquals(JevEvaluationException.FailureCategory.MALFORMED_RESPONSE, failure.category());
    assertEquals("malformed-header-id", failure.requestId().orElseThrow());
    assertNull(failure.getCause());
    for (Throwable current = failure; current != null; current = current.getCause()) {
      assertFalse(Objects.toString(current.getMessage(), "").contains("SECRET"));
    }
  }

  @Test
  void invalidChoiceResponsesRejectUnknownIncompleteAndNonunitDistributions() throws Exception {
    requestId.set("invalid-choice-id");
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
      assertEquals(JevEvaluationException.FailureCategory.MALFORMED_RESPONSE, failure.category());
      assertEquals("invalid-choice-id", failure.requestId().orElseThrow());
      assertNull(failure.getCause());
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
    JevEvaluationException connection =
        assertThrows(
            JevEvaluationException.class, () -> base.build().evaluate("state", Jev.noul("x")));
    assertEquals(JevEvaluationException.FailureCategory.IO, connection.category());
    assertTrue(connection.requestId().isEmpty());
    assertTrue(connection.httpStatusCode().isEmpty());
    assertNull(connection.getCause());
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/systemone", this::handle);
    server.start();
    delayMillis = 150;
    JevEvaluator timed =
        JevEvaluator.builder("key").baseUri(baseUri()).timeout(Duration.ofMillis(20)).build();
    JevEvaluationException timeout =
        assertThrows(JevEvaluationException.class, () -> timed.evaluate("state", Jev.noul("x")));
    assertEquals(JevEvaluationException.FailureCategory.TIMEOUT, timeout.category());
    assertTrue(timeout.requestId().isEmpty());
    assertTrue(timeout.httpStatusCode().isEmpty());
    assertNull(timeout.getCause());
  }

  @Test
  void requestIdIsSeparateFromBodyIdOnSuccessAndHttpFailure() throws Exception {
    response.set(fixture("noul-response.json"));
    requestId.set("header-single-id");
    JevEvaluator.Evaluation<Jev.NoulAnswer> result =
        evaluator().evaluateWithMetadata("state", Jev.noul("x"));
    assertEquals("header-single-id", result.requestId().orElseThrow());
    assertEquals("fixture-1", result.id().orElseThrow());
    status = 503;
    response.set("PRIVATE_BODY safe-dummy-key");
    JevEvaluationException failure =
        assertThrows(
            JevEvaluationException.class,
            () -> evaluator().evaluate("PRIVATE_STATE", Jev.noul("x")));
    assertEquals(JevEvaluationException.FailureCategory.HTTP, failure.category());
    assertEquals(503, failure.httpStatusCode().orElseThrow());
    assertEquals("header-single-id", failure.requestId().orElseThrow());
    assertEquals("evaluation failed with HTTP status 503", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void interruptionPreservesFlagWithoutLeakingACause() {
    JevEvaluator evaluator = evaluator();
    Thread.currentThread().interrupt();
    try {
      JevEvaluationException failure =
          assertThrows(
              JevEvaluationException.class,
              () -> evaluator.evaluate("PRIVATE_STATE", Jev.noul("x")));
      assertEquals(JevEvaluationException.FailureCategory.INTERRUPTED, failure.category());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(failure.requestId().isEmpty());
      assertTrue(failure.httpStatusCode().isEmpty());
      assertNull(failure.getCause());
    } finally {
      Thread.interrupted();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
  void everyArityCarriesTheSharedHeader(int arity) {
    StringBuilder answers = new StringBuilder();
    for (int i = 1; i <= arity; i++) {
      if (i > 1) answers.append(',');
      answers.append("\"question").append(i).append("\":{\"type\":\"noul\",\"noul\":0.7}");
    }
    response.set(
        "{\"model\":\"m\",\"answers\":{"
            + answers
            + "},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    Jev.NoulQuestion q = Jev.noul("x");
    JevEvaluator evaluator = evaluator();
    for (String header : List.of("shared-header", "")) {
      requestId.set(header);
      java.util.Optional<String> actual =
          switch (arity) {
            case 2 -> evaluator.evaluate("s", q, q).requestId();
            case 3 -> evaluator.evaluate("s", q, q, q).requestId();
            case 4 -> evaluator.evaluate("s", q, q, q, q).requestId();
            case 5 -> evaluator.evaluate("s", q, q, q, q, q).requestId();
            case 6 -> evaluator.evaluate("s", q, q, q, q, q, q).requestId();
            case 7 -> evaluator.evaluate("s", q, q, q, q, q, q, q).requestId();
            case 8 -> evaluator.evaluate("s", q, q, q, q, q, q, q, q).requestId();
            default -> throw new AssertionError(arity);
          };
      assertEquals(
          header.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of("shared-header"),
          actual);
    }
  }

  @Test
  void legacyConstructorsKeepTheirContracts() {
    JevEvaluationException plain = new JevEvaluationException("message");
    assertEquals(JevEvaluationException.FailureCategory.UNKNOWN, plain.category());
    assertTrue(plain.requestId().isEmpty());
    IOException cause = new IOException("caller-owned cause");
    JevEvaluationException caused = new JevEvaluationException("message", cause);
    assertEquals(cause, caused.getCause());
    assertEquals(JevEvaluationException.FailureCategory.UNKNOWN, caused.category());
    JevEvaluationException http = new JevEvaluationException("message", 429);
    assertEquals(JevEvaluationException.FailureCategory.HTTP, http.category());
    assertEquals(429, http.httpStatusCode().orElseThrow());
    assertTrue(http.requestId().isEmpty());
    JevEvaluator.Usage usage = new JevEvaluator.Usage(1, 2, java.util.OptionalDouble.empty());
    JevEvaluator.Evaluation<String> single =
        new JevEvaluator.Evaluation<>(
            "a", "m", usage, java.util.Optional.of("body-id"), java.util.Optional.empty());
    assertEquals("body-id", single.id().orElseThrow());
    assertTrue(single.requestId().isEmpty());
    JevEvaluator.Evaluation2<String, Integer> multi =
        new JevEvaluator.Evaluation2<>(
            "a", 2, "m", usage, java.util.Optional.empty(), java.util.Optional.empty());
    assertTrue(multi.requestId().isEmpty());
    assertEquals(2, multi.answer2());
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
    if (requestId.get() != null)
      exchange.getResponseHeaders().set("X-TypeSafe-Request-Id", requestId.get());
    exchange.getResponseHeaders().set("X-Request-Id", "not-a-supported-header");
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
