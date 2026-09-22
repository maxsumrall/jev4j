package io.github.maxsumrall.jev4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class JevEvaluatorTest {
  private enum Weather implements Jev.Described {
    SUN("clear"),
    RAIN("wet");

    private final String description;

    Weather(String description) {
      this.description = description;
    }

    @Override
    public String description() {
      return description;
    }
  }

  private enum Size implements Jev.ScoreLevel {
    SMALL("small"),
    LARGE("large");

    private final String description;

    Size(String description) {
      this.description = description;
    }

    @Override
    public String description() {
      return description;
    }
  }

  private HttpServer server;
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private volatile int status;
  private volatile long delayMillis;

  @BeforeEach
  void startServer() throws IOException {
    response.set(envelope("{\"type\":\"noul\",\"noul\":0.8}"));
    status = 200;
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/systemone", this::handle);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void sendsExactNoulRequestAndDoesNotSerializeThreshold() {
    var evaluator = evaluator();
    var answer =
        evaluator.evaluate(Jev.noul("Rain?").describe(true, "yes").threshold(0.75), "clouds");

    assertEquals(0.8, answer.probabilityTrue());
    assertEquals(0.75, answer.threshold());
    assertEquals("Bearer test-key", authorization.get());
    assertEquals("/v1/systemone", path.get());
    assertEquals(
        "{\"state\":\"clouds\",\"model\":\"test-model\",\"questions\":{\"question\":{\"type\":\"noul\",\"instructions\":\"Rain?\",\"criteria\":{\"true\":\"yes\"}}}}",
        requestBody.get());
    assertFalse(Objects.requireNonNull(requestBody.get()).contains("0.75"));

    assertEquals(0.8, evaluator.evaluate(Jev.noul("Rain?"), "clouds").probabilityTrue());
  }

  @Test
  void decodesChoiceAndUsesStableEnumNames() {
    response.set(
        envelope(
            "{\"type\":\"choice\",\"choice\":\"RAIN\",\"probabilities\":{\"SUN\":0.25,\"RAIN\":0.75},\"confidence\":0.61}"));
    var answer =
        evaluator().evaluate(Jev.choice(Weather.class, "Weather?").minConfidence(0.6), "state");

    assertEquals(Weather.RAIN, answer.value());
    assertEquals(0.25, answer.probabilities().get(Weather.SUN));
    assertEquals(0.61, answer.confidence());
    assertTrue(answer.meetsThresholds());
    assertTrue(
        Objects.requireNonNull(requestBody.get())
            .contains("\"criteria\":{\"SUN\":\"clear\",\"RAIN\":\"wet\"}"));
  }

  @Test
  void decodesBothScoreFormsPreservingFractionAndConfidence() {
    response.set(scoreEnvelope(0.75, 0.44));
    var plain =
        evaluator().evaluate(Jev.score("Quality?").level("bad").level("good").build(), "state");
    assertEquals(0.75, plain.value());
    assertEquals(0.44, plain.confidence());
    assertTrue(
        Objects.requireNonNull(requestBody.get()).contains("\"criteria\":[\"bad\",\"good\"]"));

    response.set(scoreEnvelope(1.0, 0.9));
    var typed = evaluator().evaluate(Jev.score(Size.class, "Size?"), "state");
    assertEquals(Size.LARGE, typed.nearestLevel());
    assertEquals(List.of(0.25, 0.75), List.copyOf(typed.probabilities().values()));
    assertTrue(
        Objects.requireNonNull(requestBody.get()).contains("\"criteria\":[\"small\",\"large\"]"));
  }

  @Test
  void preservesOpenRouterMetadataAndIgnoresUnknownFields() {
    response.set(
        "{\"id\":\"request-1\",\"provider\":\"TypeSafe\",\"model\":\"typesafe/jev-1.13\","
            + "\"answers\":{\"question\":{\"type\":\"noul\",\"noul\":0.8,\"future\":true}},"
            + "\"usage\":{\"input_tokens\":12,\"output_tokens\":3,\"cost\":0.00003},\"extra\":{}}");
    var result = evaluator().evaluateWithMetadata(Jev.noul("Question?"), "state");

    assertEquals("typesafe/jev-1.13", result.model());
    assertEquals("request-1", result.id().orElseThrow());
    assertEquals("TypeSafe", result.provider().orElseThrow());
    assertEquals(12, result.usage().inputTokens());
    assertEquals(0.00003, result.usage().cost().orElseThrow());
  }

  @Test
  void rejectsIncompleteUnknownAndInvalidAnswers() {
    var evaluator = evaluator();
    for (String answer :
        List.of(
            "{\"type\":\"noul\"}",
            "{\"type\":\"choice\",\"choice\":\"HAIL\",\"probabilities\":{\"SUN\":0.5,\"RAIN\":0.5},\"confidence\":1}",
            "{\"type\":\"choice\",\"choice\":\"SUN\",\"probabilities\":{\"SUN\":0.5},\"confidence\":1}",
            "{\"type\":\"choice\",\"choice\":\"SUN\",\"probabilities\":{\"SUN\":null,\"RAIN\":0.5},\"confidence\":1}",
            "{\"type\":\"choice\",\"choice\":\"SUN\",\"probabilities\":{\"SUN\":1.1,\"RAIN\":0},\"confidence\":1}")) {
      response.set(envelope(answer));
      if (answer.contains("choice")) {
        assertThrows(
            JevEvaluationException.class,
            () -> evaluator.evaluate(Jev.choice(Weather.class, "x"), "state"));
      } else {
        assertThrows(
            JevEvaluationException.class, () -> evaluator.evaluate(Jev.noul("x"), "state"));
      }
    }
    response.set(envelope("{\"type\":\"noul\",\"noul\":1e400}"));
    assertThrows(JevEvaluationException.class, () -> evaluator.evaluate(Jev.noul("x"), "state"));
    response.set(scoreEnvelope(2.0, 0.8));
    assertThrows(
        JevEvaluationException.class,
        () -> evaluator.evaluate(Jev.score("x").level("low").level("high").build(), "state"));
    response.set("not-json");
    assertThrows(JevEvaluationException.class, () -> evaluator.evaluate(Jev.noul("x"), "state"));
  }

  @Test
  void reportsHttpNetworkAndTimeoutWithoutLeakingSecretsOrState() throws IOException {
    for (int code : List.of(401, 429, 500)) {
      status = code;
      var error =
          assertThrows(
              JevEvaluationException.class,
              () -> evaluator().evaluate(Jev.noul("x"), "private state"));
      String message = Objects.requireNonNull(error.getMessage());
      assertTrue(message.contains(Integer.toString(code)));
      assertFalse(message.contains("test-key"));
      assertFalse(message.contains("private state"));
    }

    server.stop(0);
    var network =
        assertThrows(
            JevEvaluationException.class, () -> evaluator().evaluate(Jev.noul("x"), "state"));
    assertTrue(Objects.requireNonNull(network.getMessage()).contains("request failed"));

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/systemone", this::handle);
    server.start();
    status = 200;
    delayMillis = 200;
    var timeoutEvaluator =
        JevEvaluator.builder("key").baseUri(baseUri()).timeout(Duration.ofMillis(20)).build();
    assertThrows(
        JevEvaluationException.class, () -> timeoutEvaluator.evaluate(Jev.noul("x"), "state"));
  }

  @Test
  void validatesConfigurationAndUsesInjectedClient() {
    assertThrows(IllegalArgumentException.class, () -> JevEvaluator.builder(" "));
    assertThrows(
        IllegalArgumentException.class, () -> JevEvaluator.builder("x").timeout(Duration.ZERO));
    HttpClient configured = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var evaluator =
        JevEvaluator.builder("test-key").baseUri(baseUri()).httpClient(configured).build();
    assertEquals(0.8, evaluator.evaluate(Jev.noul("x"), "state").probabilityTrue());
  }

  @Test
  void builderBranchesAreIndependentAndPreserveConfiguredClient() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var base = JevEvaluator.builder("test-key").baseUri(baseUri()).httpClient(client);
    var first = base.model("first-model");
    var second = base.model("second-model").timeout(Duration.ofSeconds(1));

    base.build().evaluate(Jev.noul("x"), "state");
    assertTrue(Objects.requireNonNull(requestBody.get()).contains("\"model\":\"jev-latest\""));
    first.build().evaluate(Jev.noul("x"), "state");
    assertTrue(Objects.requireNonNull(requestBody.get()).contains("\"model\":\"first-model\""));
    second.build().evaluate(Jev.noul("x"), "state");
    assertTrue(Objects.requireNonNull(requestBody.get()).contains("\"model\":\"second-model\""));
  }

  @Test
  void rejectsInvalidBaseUrisImmediately() {
    for (String uri :
        List.of(
            "ftp://example.com",
            "file:///tmp/api",
            "https:///missing-host",
            "https://user@example.com",
            "https://example.com?secret=x",
            "https://example.com#fragment")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> JevEvaluator.builder("key").baseUri(URI.create(uri)));
    }
  }

  @Test
  void malformedResponsesDoNotLeakResponseTextThroughExceptionChain() {
    String sentinel = "SENSITIVE_RESPONSE_SENTINEL";
    response.set("{\"unterminated\":\"" + sentinel);
    Throwable error =
        assertThrows(
            JevEvaluationException.class, () -> evaluator().evaluate(Jev.noul("x"), "state"));

    for (Throwable current = error; current != null; current = current.getCause()) {
      assertFalse(Objects.toString(current.getMessage(), "").contains(sentinel));
    }

    response.set(envelope("{\"type\":\"" + sentinel + "\",\"noul\":0.8}"));
    error =
        assertThrows(
            JevEvaluationException.class, () -> evaluator().evaluate(Jev.noul("x"), "state"));
    assertFalse(Objects.requireNonNull(error.getMessage()).contains(sentinel));
  }

  @Test
  void interruptionIsPreservedWithSafeCause() {
    assertFalse(Thread.currentThread().isInterrupted());
    try {
      var evaluator =
          JevEvaluator.builder("key")
              .baseUri(URI.create("https://example.com"))
              .httpClient(new InterruptingHttpClient())
              .build();
      var error =
          assertThrows(
              JevEvaluationException.class, () -> evaluator.evaluate(Jev.noul("x"), "state"));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(error.getCause() instanceof InterruptedException);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void publicMetadataRecordsEnforceInvariants() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new JevEvaluator.Usage(-1, 0, OptionalDouble.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JevEvaluator.Usage(0, -1, OptionalDouble.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JevEvaluator.Usage(0, 0, OptionalDouble.of(Double.NaN)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JevEvaluator.Usage(0, 0, OptionalDouble.of(Double.POSITIVE_INFINITY)));
    assertEquals(-1.5, new JevEvaluator.Usage(0, 0, OptionalDouble.of(-1.5)).cost().orElseThrow());
  }

  private static final class InterruptingHttpClient extends HttpClient {
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws InterruptedException {
      throw new InterruptedException("interrupted by test");
    }

    @Override
    public Optional<java.net.CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<java.net.ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public javax.net.ssl.SSLContext sslContext() {
      return Objects.requireNonNull(HttpClient.newHttpClient().sslContext());
    }

    @Override
    public javax.net.ssl.SSLParameters sslParameters() {
      return new javax.net.ssl.SSLParameters();
    }

    @Override
    public Optional<java.net.Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_2;
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }
  }

  private JevEvaluator evaluator() {
    return JevEvaluator.builder("test-key")
        .baseUri(baseUri())
        .model("test-model")
        .timeout(Duration.ofSeconds(2))
        .build();
  }

  private URI baseUri() {
    return URI.create("http://localhost:" + server.getAddress().getPort());
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      if (delayMillis > 0) Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    path.set(exchange.getRequestURI().getPath());
    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    requestBody.set(
        new String(
            exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    byte[] bytes =
        Objects.requireNonNull(response.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String envelope(String answer) {
    return "{\"model\":\"jev-test\",\"answers\":{\"question\":"
        + answer
        + "},\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}";
  }

  private static String scoreEnvelope(double score, double confidence) {
    return envelope(
        "{\"type\":\"score\",\"score\":"
            + score
            + ",\"legend\":{\"0\":\"low\",\"1\":\"high\"},\"probabilities\":{\"0\":0.25,\"1\":0.75},\"confidence\":"
            + confidence
            + "}");
  }
}
