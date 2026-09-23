package io.github.maxsumrall.jev4j;

import com.google.errorprone.annotations.Var;
import io.github.maxsumrall.jev4j.JevEvaluationException.FailureCategory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Immutable, thread-safe HTTP client for evaluating Jev questions.
 *
 * <p>Async methods validate and serialize on the calling thread, then dispatch one request without
 * waiting for its response. They reuse this evaluator's HTTP client and do not retry. Local
 * validation failures throw immediately; transport and response failures complete the returned
 * future exceptionally with sanitized {@link JevEvaluationException}s.
 *
 * <p>Cancel the original returned future to request best-effort transport cancellation. Both {@code
 * cancel(false)} and {@code cancel(true)} request {@code cancel(true)} on the transport future;
 * neither interrupts the caller or response decoder. Cancellation is distinct from an evaluation
 * failure and does not guarantee that the request was not sent, provider work stopped, or charges
 * were avoided. Canceling a caller-created dependent stage does not cancel the original operation.
 * Waiting with {@code get(timeout, unit)}, interrupting a waiting thread, or using {@code
 * orTimeout} does not abort the request. The configured timeout is an HTTP request timeout, not an
 * end-to-end deadline covering preparation, decoding, or user continuations.
 *
 * <p>Completion callbacks may run inline or on a completing thread. Use an explicit caller-owned
 * executor for expensive asynchronous continuations. The evaluator creates no additional executor
 * and never closes a supplied client or its executor.
 */
public final class JevEvaluator {
  public static final URI TYPESAFE_BASE_URI = URI.create("https://api.typesafe.ai");
  public static final URI OPENROUTER_BASE_URI = URI.create("https://openrouter.ai/api");

  private static final String QUESTION_KEY = "question";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient httpClient;
  private final URI endpoint;
  private final String apiKey;
  private final String model;
  private final Duration timeout;

  private JevEvaluator(Builder builder) {
    httpClient = builder.httpClient;
    String base = builder.baseUri.toString();
    endpoint =
        URI.create(
            (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/systemone");
    apiKey = builder.apiKey;
    model = builder.model;
    timeout = builder.timeout;
  }

  public static Builder builder(String apiKey) {
    return new Builder(apiKey);
  }

  public Jev.NoulAnswer evaluate(String state, Jev.NoulQuestion question) {
    return evaluateWithMetadata(state, question).answer();
  }

  /**
   * Evaluates one Noul and applies its inclusive threshold, which defaults to {@code 0.5}. This
   * terminal operation makes exactly one provider request and may incur charges.
   */
  public boolean test(String state, Jev.NoulQuestion question) {
    return evaluate(state, question).isTrue();
  }

  /** Evaluates a state snapshot and applies the question's inclusive threshold. */
  public boolean test(Jev.State state, Jev.NoulQuestion question) {
    return evaluate(state, question).isTrue();
  }

  /** Evaluates a state snapshot, preserving the question's answer type. */
  public <A> A evaluate(Jev.State state, Jev.Question<A> question) {
    return evaluateWithMetadata(state, question).answer();
  }

  public <E extends Enum<E>> Jev.ChoiceAnswer<E> evaluate(
      String state, Jev.ChoiceQuestion<E> question) {
    return evaluateWithMetadata(state, question).answer();
  }

  public <E extends Enum<E>> Jev.EnumScoreAnswer<E> evaluate(
      String state, Jev.EnumScoreQuestion<E> question) {
    return evaluateWithMetadata(state, question).answer();
  }

  public Jev.ScoreAnswer evaluate(String state, Jev.ScoreQuestion question) {
    return evaluateWithMetadata(state, question).answer();
  }

  public Evaluation<Jev.NoulAnswer> evaluateWithMetadata(String state, Jev.NoulQuestion question) {
    return evaluateWithMetadata(Jev.State.from(state), question);
  }

  public <E extends Enum<E>> Evaluation<Jev.ChoiceAnswer<E>> evaluateWithMetadata(
      String state, Jev.ChoiceQuestion<E> question) {
    return evaluateWithMetadata(Jev.State.from(state), question);
  }

  public <E extends Enum<E>> Evaluation<Jev.EnumScoreAnswer<E>> evaluateWithMetadata(
      String state, Jev.EnumScoreQuestion<E> question) {
    return evaluateWithMetadata(Jev.State.from(state), question);
  }

  public Evaluation<Jev.ScoreAnswer> evaluateWithMetadata(
      String state, Jev.ScoreQuestion question) {
    return evaluateWithMetadata(Jev.State.from(state), question);
  }

  /** Evaluates a state snapshot and returns request-level metadata with the typed answer. */
  public <A> Evaluation<A> evaluateWithMetadata(Jev.State state, Jev.Question<A> question) {
    Prepared<A> prepared = prepare(question);
    Evaluation<List<Object>> result = exchange(List.of(prepared), state, QUESTION_KEY);
    return new Evaluation<>(
        prepared.cast(result.answer().get(0)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates literal text asynchronously, preserving the question's answer type. */
  public <A> CompletableFuture<A> evaluateAsync(String state, Jev.Question<A> question) {
    return evaluateAsync(Jev.State.from(state), question);
  }

  /** Evaluates a state snapshot asynchronously, preserving the question's answer type. */
  public <A> CompletableFuture<A> evaluateAsync(Jev.State state, Jev.Question<A> question) {
    Prepared<A> prepared = prepare(question);
    return exchangeAsync(
        List.of(prepared), state, QUESTION_KEY, result -> prepared.cast(result.answer().get(0)));
  }

  /** Evaluates literal text asynchronously with request-level metadata. */
  public <A> CompletableFuture<Evaluation<A>> evaluateWithMetadataAsync(
      String state, Jev.Question<A> question) {
    return evaluateWithMetadataAsync(Jev.State.from(state), question);
  }

  /** Evaluates a state snapshot asynchronously with request-level metadata. */
  public <A> CompletableFuture<Evaluation<A>> evaluateWithMetadataAsync(
      Jev.State state, Jev.Question<A> question) {
    Prepared<A> prepared = prepare(question);
    return exchangeAsync(
        List.of(prepared),
        state,
        QUESTION_KEY,
        result ->
            new Evaluation<>(
                prepared.cast(result.answer().get(0)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  /** Evaluates literal text asynchronously and applies the question's inclusive threshold. */
  public CompletableFuture<Boolean> testAsync(String state, Jev.NoulQuestion question) {
    return testAsync(Jev.State.from(state), question);
  }

  /** Evaluates a state snapshot asynchronously and applies the question's inclusive threshold. */
  public CompletableFuture<Boolean> testAsync(Jev.State state, Jev.NoulQuestion question) {
    Prepared<Jev.NoulAnswer> prepared = prepare(question);
    return exchangeAsync(
        List.of(prepared),
        state,
        QUESTION_KEY,
        result -> prepared.cast(result.answer().get(0)).isTrue());
  }

  private HttpRequest request(
      List<Prepared<?>> preparedQuestions, Jev.State state, String firstKey) {
    Objects.requireNonNull(state, "state");
    ObjectNode root = JSON.createObjectNode();
    state.putInto(root);
    root.put("model", model);
    ObjectNode questions = root.putObject("questions");
    for (int i = 0; i < preparedQuestions.size(); i++) {
      String key = preparedQuestions.size() == 1 ? firstKey : "question" + (i + 1);
      questions.set(key, preparedQuestions.get(i).node());
    }
    return HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(root)))
        .build();
  }

  private Evaluation<List<Object>> exchange(
      List<Prepared<?>> preparedQuestions, Jev.State state, String firstKey) {
    HttpRequest request = request(preparedQuestions, state, firstKey);
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JevEvaluationException(
          "evaluation interrupted",
          FailureCategory.INTERRUPTED,
          OptionalInt.empty(),
          Optional.empty());
    } catch (HttpTimeoutException e) {
      throw new JevEvaluationException(
          "evaluation request timed out",
          FailureCategory.TIMEOUT,
          OptionalInt.empty(),
          Optional.empty());
    } catch (IOException e) {
      throw new JevEvaluationException(
          "evaluation request failed", FailureCategory.IO, OptionalInt.empty(), Optional.empty());
    }
    return decode(preparedQuestions, firstKey, response);
  }

  // These callbacks complete the public result themselves; their dependent stages are not results.
  @SuppressWarnings("FutureReturnValueIgnored")
  private <R> CompletableFuture<R> exchangeAsync(
      List<Prepared<?>> preparedQuestions,
      Jev.State state,
      String firstKey,
      Function<Evaluation<List<Object>>, R> projection) {
    // Preparation is deliberately outside the asynchronous failure boundary.
    HttpRequest request = request(preparedQuestions, state, firstKey);
    CompletableFuture<HttpResponse<String>> transport;
    try {
      transport = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(asyncFailure(failure));
    }
    CompletableFuture<R> result = new CompletableFuture<>();
    // Keep the original transport, even when an injected client returns an ordinary future.
    result.whenComplete(
        (value, failure) -> {
          if (result.isCancelled()) transport.cancel(true);
        });
    transport.whenComplete(
        (response, failure) -> {
          if (result.isDone()) return;
          if (failure != null) {
            result.completeExceptionally(asyncFailure(failure));
            return;
          }
          try {
            result.complete(
                projection.apply(
                    decode(preparedQuestions, firstKey, Objects.requireNonNull(response))));
          } catch (JevEvaluationException invalidResponse) {
            result.completeExceptionally(invalidResponse);
          } catch (Throwable unexpected) {
            // Do not strand the public future when decoding/projection fails in a callback.
            result.completeExceptionally(asyncFailure(unexpected));
          }
        });
    // Returning a dependent stage here would disconnect cancellation from the transport.
    return result;
  }

  private static RuntimeException asyncFailure(Throwable failure) {
    @Var Throwable cause = failure;
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    if (cause instanceof CancellationException)
      return new CancellationException("evaluation cancelled");
    if (cause instanceof HttpTimeoutException)
      return new JevEvaluationException(
          "evaluation request timed out",
          FailureCategory.TIMEOUT,
          OptionalInt.empty(),
          Optional.empty());
    return new JevEvaluationException(
        "evaluation request failed",
        cause instanceof IOException ? FailureCategory.IO : FailureCategory.UNKNOWN,
        OptionalInt.empty(),
        Optional.empty());
  }

  private Evaluation<List<Object>> decode(
      List<Prepared<?>> preparedQuestions, String firstKey, HttpResponse<String> response) {
    Optional<String> requestId =
        response.headers().firstValue("x-typesafe-request-id").filter(value -> !value.isBlank());
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new JevEvaluationException(
          "evaluation failed with HTTP status " + response.statusCode(),
          FailureCategory.HTTP,
          OptionalInt.of(response.statusCode()),
          requestId);
    try {
      JsonNode body = JSON.readTree(response.body());
      if (body == null || !body.isObject()) throw malformed("response must be a JSON object");
      JsonNode answers = body.get("answers");
      if (answers == null || !answers.isObject()) throw malformed("missing object 'answers'");
      List<Object> values = new ArrayList<>();
      for (int i = 0; i < preparedQuestions.size(); i++) {
        String key = preparedQuestions.size() == 1 ? firstKey : "question" + (i + 1);
        JsonNode answer = answers.get(key);
        if (answer == null || !answer.isObject())
          throw malformed("missing answer for '" + key + "'");
        values.add(preparedQuestions.get(i).decoder().decode(answer));
      }
      String responseModel = requiredText(body, "model");
      JsonNode usageNode = body.get("usage");
      if (usageNode == null || !usageNode.isObject()) throw malformed("missing object 'usage'");
      Usage usage =
          new Usage(
              requiredLong(usageNode, "input_tokens"),
              requiredLong(usageNode, "output_tokens"),
              optionalFinite(usageNode, "cost"));
      return new Evaluation<>(
          List.copyOf(values),
          responseModel,
          usage,
          optionalText(body, "id"),
          optionalText(body, "provider"),
          requestId);
    } catch (JevEvaluationException e) {
      throw new JevEvaluationException(
          Objects.requireNonNull(e.getMessage()),
          FailureCategory.MALFORMED_RESPONSE,
          OptionalInt.empty(),
          requestId);
    } catch (JacksonException e) {
      // Jackson's exception and cause messages can quote arbitrary response content.
      throw new JevEvaluationException(
          "malformed evaluation response: invalid JSON",
          FailureCategory.MALFORMED_RESPONSE,
          OptionalInt.empty(),
          requestId);
    } catch (IllegalArgumentException e) {
      throw new JevEvaluationException(
          "malformed evaluation response: invalid evaluation response",
          FailureCategory.MALFORMED_RESPONSE,
          OptionalInt.empty(),
          requestId);
    }
  }

  @SuppressWarnings("unchecked")
  private static <A> Prepared<A> prepare(Jev.Question<A> question) {
    Objects.requireNonNull(question, "question");
    if (question instanceof Jev.NoulQuestion noul) {
      return (Prepared<A>)
          new Prepared<>(
              questionNode("noul", noul.instructions(), noulCriteria(noul.descriptions())),
              answer -> {
                requireType(answer, "noul");
                return noul.answer(requiredProbability(answer, "noul"));
              },
              Jev.NoulAnswer.class);
    }
    if (question instanceof Jev.ChoiceQuestion<?> choice)
      return (Prepared<A>) prepareChoice(choice);
    if (question instanceof Jev.EnumScoreQuestion<?> score)
      return (Prepared<A>) prepareEnumScore(score);
    if (question instanceof Jev.ScoreQuestion score) {
      ArrayNode criteria = JSON.createArrayNode();
      score.levels().forEach(criteria::add);
      return (Prepared<A>)
          new Prepared<>(
              questionNode("score", score.instructions(), criteria),
              answer -> {
                requireType(answer, "score");
                return score.answer(
                    requiredNumber(answer, "score"),
                    indexedProbabilities(answer, score.levels().size()),
                    requiredProbability(answer, "confidence"));
              },
              Jev.ScoreAnswer.class);
    }
    throw new IllegalArgumentException("unsupported question type");
  }

  private static <E extends Enum<E>> Prepared<Jev.ChoiceAnswer<E>> prepareChoice(
      Jev.ChoiceQuestion<E> question) {
    ObjectNode criteria = JSON.createObjectNode();
    question.descriptions().forEach((key, value) -> criteria.put(key.name(), value));
    return new Prepared<>(
        questionNode("choice", question.instructions(), criteria),
        answer -> {
          requireType(answer, "choice");
          E value;
          try {
            value = Enum.valueOf(question.optionType(), requiredText(answer, "choice"));
          } catch (IllegalArgumentException e) {
            throw malformed("choice is not one of the declared options");
          }
          return question.answer(
              value,
              enumProbabilities(answer, question.optionType()),
              requiredProbability(answer, "confidence"));
        },
        Jev.ChoiceAnswer.class);
  }

  private static <E extends Enum<E>> Prepared<Jev.EnumScoreAnswer<E>> prepareEnumScore(
      Jev.EnumScoreQuestion<E> question) {
    ArrayNode criteria = JSON.createArrayNode();
    for (E level : question.levelType().getEnumConstants())
      criteria.add(question.descriptions().get(level));
    return new Prepared<>(
        questionNode("score", question.instructions(), criteria),
        answer -> {
          requireType(answer, "score");
          List<Double> values =
              indexedProbabilities(answer, question.levelType().getEnumConstants().length);
          Map<E, Double> probabilities = new EnumMap<>(question.levelType());
          E[] levels = question.levelType().getEnumConstants();
          for (int i = 0; i < levels.length; i++) probabilities.put(levels[i], values.get(i));
          return question.answer(
              requiredNumber(answer, "score"),
              probabilities,
              requiredProbability(answer, "confidence"));
        },
        Jev.EnumScoreAnswer.class);
  }

  private static ObjectNode questionNode(String type, String instructions, JsonNode criteria) {
    ObjectNode node = JSON.createObjectNode().put("type", type).put("instructions", instructions);
    if (!criteria.isEmpty()) node.set("criteria", criteria);
    return node;
  }

  private static ObjectNode noulCriteria(Map<Boolean, String> descriptions) {
    ObjectNode node = JSON.createObjectNode();
    descriptions.forEach((key, value) -> node.put(key.toString(), value));
    return node;
  }

  private static void requireType(JsonNode answer, String expected) {
    String actual = requiredText(answer, "type");
    if (!expected.equals(actual)) throw malformed("answer type does not match the question type");
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank())
      throw malformed("missing text '" + field + "'");
    return value.textValue();
  }

  private static double requiredNumber(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue()))
      throw malformed("missing or non-finite number '" + field + "'");
    return value.doubleValue();
  }

  private static double requiredProbability(JsonNode node, String field) {
    double value = requiredNumber(node, field);
    if (value < 0 || value > 1) throw malformed("number '" + field + "' is outside [0, 1]");
    return value;
  }

  private static long requiredLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) throw malformed("missing or invalid integer '" + field + "'");
    return value.longValue();
  }

  private static OptionalDouble optionalFinite(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) return OptionalDouble.empty();
    double number = requiredNumber(node, field);
    return OptionalDouble.of(number);
  }

  private static Optional<String> optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) return Optional.empty();
    if (!value.isTextual()) throw malformed("invalid text '" + field + "'");
    return Optional.of(value.textValue());
  }

  private static <E extends Enum<E>> Map<E, Double> enumProbabilities(
      JsonNode answer, Class<E> type) {
    JsonNode node = answer.get("probabilities");
    if (node == null || !node.isObject()) throw malformed("missing object 'probabilities'");
    Map<E, Double> result = new EnumMap<>(type);
    for (E value : type.getEnumConstants())
      result.put(value, requiredProbability(node, value.name()));
    if (node.size() != result.size())
      throw malformed("probabilities contain unexpected choice options");
    return result;
  }

  private static List<Double> indexedProbabilities(JsonNode answer, int count) {
    JsonNode node = answer.get("probabilities");
    if (node == null || !node.isObject()) throw malformed("missing object 'probabilities'");
    List<Double> result = new ArrayList<>();
    for (int i = 0; i < count; i++) result.add(requiredProbability(node, Integer.toString(i)));
    if (node.size() != count) throw malformed("probabilities contain unexpected score levels");
    return result;
  }

  private static JevEvaluationException malformed(String message) {
    return new JevEvaluationException("malformed evaluation response: " + message);
  }

  private record Prepared<A>(ObjectNode node, AnswerDecoder<A> decoder, Class<?> answerType) {
    private Prepared {
      Objects.requireNonNull(node, "node");
      Objects.requireNonNull(decoder, "decoder");
      Objects.requireNonNull(answerType, "answerType");
    }

    private A cast(Object answer) {
      Object checked = answerType.cast(answer);
      return decoderTypeCast(checked);
    }

    @SuppressWarnings("unchecked")
    private A decoderTypeCast(Object answer) {
      return (A) answer;
    }
  }

  // BEGIN GENERATED MULTI-QUESTION API

  public <A1, A2> Evaluation2<A1, A2> evaluate(
      String state, Jev.Question<A1> question1, Jev.Question<A2> question2) {
    return evaluate(Jev.State.from(state), question1, question2);
  }

  public <A1, A2> Evaluation2<A1, A2> evaluate(
      Jev.State state, Jev.Question<A1> question1, Jev.Question<A2> question2) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Evaluation<List<Object>> result = exchange(List.of(prepared1, prepared2), state, "question1");
    return new Evaluation2<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 2 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2> CompletableFuture<Evaluation2<A1, A2>> evaluateAsync(
      String state, Jev.Question<A1> question1, Jev.Question<A2> question2) {
    return evaluateAsync(Jev.State.from(state), question1, question2);
  }

  /** Evaluates 2 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2> CompletableFuture<Evaluation2<A1, A2>> evaluateAsync(
      Jev.State state, Jev.Question<A1> question1, Jev.Question<A2> question2) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    return exchangeAsync(
        List.of(prepared1, prepared2),
        state,
        "question1",
        result ->
            new Evaluation2<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function2<A1, A2, R> {
    R apply(A1 answer1, A2 answer2);
  }

  public record Evaluation2<A1, A2>(
      A1 answer1,
      A2 answer2,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation2(
        A1 answer1,
        A2 answer2,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(answer1, answer2, model, usage, id, provider, Optional.empty());
    }

    public Evaluation2 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(Function2<? super A1, ? super A2, ? extends R> mapper) {
      return Objects.requireNonNull(mapper, "mapper").apply(answer1, answer2);
    }
  }

  public <A1, A2, A3> Evaluation3<A1, A2, A3> evaluate(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3) {
    return evaluate(Jev.State.from(state), question1, question2, question3);
  }

  public <A1, A2, A3> Evaluation3<A1, A2, A3> evaluate(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Evaluation<List<Object>> result =
        exchange(List.of(prepared1, prepared2, prepared3), state, "question1");
    return new Evaluation3<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        prepared3.cast(result.answer().get(2)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 3 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3> CompletableFuture<Evaluation3<A1, A2, A3>> evaluateAsync(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3) {
    return evaluateAsync(Jev.State.from(state), question1, question2, question3);
  }

  /** Evaluates 3 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3> CompletableFuture<Evaluation3<A1, A2, A3>> evaluateAsync(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    return exchangeAsync(
        List.of(prepared1, prepared2, prepared3),
        state,
        "question1",
        result ->
            new Evaluation3<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                prepared3.cast(result.answer().get(2)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function3<A1, A2, A3, R> {
    R apply(A1 answer1, A2 answer2, A3 answer3);
  }

  public record Evaluation3<A1, A2, A3>(
      A1 answer1,
      A2 answer2,
      A3 answer3,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation3(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(answer1, answer2, answer3, model, usage, id, provider, Optional.empty());
    }

    public Evaluation3 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(answer3, "answer3");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(Function3<? super A1, ? super A2, ? super A3, ? extends R> mapper) {
      return Objects.requireNonNull(mapper, "mapper").apply(answer1, answer2, answer3);
    }
  }

  public <A1, A2, A3, A4> Evaluation4<A1, A2, A3, A4> evaluate(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4) {
    return evaluate(Jev.State.from(state), question1, question2, question3, question4);
  }

  public <A1, A2, A3, A4> Evaluation4<A1, A2, A3, A4> evaluate(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Evaluation<List<Object>> result =
        exchange(List.of(prepared1, prepared2, prepared3, prepared4), state, "question1");
    return new Evaluation4<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        prepared3.cast(result.answer().get(2)),
        prepared4.cast(result.answer().get(3)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 4 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4> CompletableFuture<Evaluation4<A1, A2, A3, A4>> evaluateAsync(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4) {
    return evaluateAsync(Jev.State.from(state), question1, question2, question3, question4);
  }

  /** Evaluates 4 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4> CompletableFuture<Evaluation4<A1, A2, A3, A4>> evaluateAsync(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    return exchangeAsync(
        List.of(prepared1, prepared2, prepared3, prepared4),
        state,
        "question1",
        result ->
            new Evaluation4<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                prepared3.cast(result.answer().get(2)),
                prepared4.cast(result.answer().get(3)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function4<A1, A2, A3, A4, R> {
    R apply(A1 answer1, A2 answer2, A3 answer3, A4 answer4);
  }

  public record Evaluation4<A1, A2, A3, A4>(
      A1 answer1,
      A2 answer2,
      A3 answer3,
      A4 answer4,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation4(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        A4 answer4,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(answer1, answer2, answer3, answer4, model, usage, id, provider, Optional.empty());
    }

    public Evaluation4 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(answer3, "answer3");
      Objects.requireNonNull(answer4, "answer4");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(
        Function4<? super A1, ? super A2, ? super A3, ? super A4, ? extends R> mapper) {
      return Objects.requireNonNull(mapper, "mapper").apply(answer1, answer2, answer3, answer4);
    }
  }

  public <A1, A2, A3, A4, A5> Evaluation5<A1, A2, A3, A4, A5> evaluate(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5) {
    return evaluate(Jev.State.from(state), question1, question2, question3, question4, question5);
  }

  public <A1, A2, A3, A4, A5> Evaluation5<A1, A2, A3, A4, A5> evaluate(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Evaluation<List<Object>> result =
        exchange(
            List.of(prepared1, prepared2, prepared3, prepared4, prepared5), state, "question1");
    return new Evaluation5<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        prepared3.cast(result.answer().get(2)),
        prepared4.cast(result.answer().get(3)),
        prepared5.cast(result.answer().get(4)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 5 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5> CompletableFuture<Evaluation5<A1, A2, A3, A4, A5>> evaluateAsync(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5) {
    return evaluateAsync(
        Jev.State.from(state), question1, question2, question3, question4, question5);
  }

  /** Evaluates 5 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5> CompletableFuture<Evaluation5<A1, A2, A3, A4, A5>> evaluateAsync(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    return exchangeAsync(
        List.of(prepared1, prepared2, prepared3, prepared4, prepared5),
        state,
        "question1",
        result ->
            new Evaluation5<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                prepared3.cast(result.answer().get(2)),
                prepared4.cast(result.answer().get(3)),
                prepared5.cast(result.answer().get(4)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function5<A1, A2, A3, A4, A5, R> {
    R apply(A1 answer1, A2 answer2, A3 answer3, A4 answer4, A5 answer5);
  }

  public record Evaluation5<A1, A2, A3, A4, A5>(
      A1 answer1,
      A2 answer2,
      A3 answer3,
      A4 answer4,
      A5 answer5,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation5(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        A4 answer4,
        A5 answer5,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(
          answer1,
          answer2,
          answer3,
          answer4,
          answer5,
          model,
          usage,
          id,
          provider,
          Optional.empty());
    }

    public Evaluation5 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(answer3, "answer3");
      Objects.requireNonNull(answer4, "answer4");
      Objects.requireNonNull(answer5, "answer5");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(
        Function5<? super A1, ? super A2, ? super A3, ? super A4, ? super A5, ? extends R> mapper) {
      return Objects.requireNonNull(mapper, "mapper")
          .apply(answer1, answer2, answer3, answer4, answer5);
    }
  }

  public <A1, A2, A3, A4, A5, A6> Evaluation6<A1, A2, A3, A4, A5, A6> evaluate(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5,
      Jev.Question<A6> question6) {
    return evaluate(
        Jev.State.from(state), question1, question2, question3, question4, question5, question6);
  }

  public <A1, A2, A3, A4, A5, A6> Evaluation6<A1, A2, A3, A4, A5, A6> evaluate(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5,
      Jev.Question<A6> question6) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Prepared<A6> prepared6 = prepare(question6);
    Evaluation<List<Object>> result =
        exchange(
            List.of(prepared1, prepared2, prepared3, prepared4, prepared5, prepared6),
            state,
            "question1");
    return new Evaluation6<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        prepared3.cast(result.answer().get(2)),
        prepared4.cast(result.answer().get(3)),
        prepared5.cast(result.answer().get(4)),
        prepared6.cast(result.answer().get(5)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 6 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5, A6>
      CompletableFuture<Evaluation6<A1, A2, A3, A4, A5, A6>> evaluateAsync(
          String state,
          Jev.Question<A1> question1,
          Jev.Question<A2> question2,
          Jev.Question<A3> question3,
          Jev.Question<A4> question4,
          Jev.Question<A5> question5,
          Jev.Question<A6> question6) {
    return evaluateAsync(
        Jev.State.from(state), question1, question2, question3, question4, question5, question6);
  }

  /** Evaluates 6 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5, A6>
      CompletableFuture<Evaluation6<A1, A2, A3, A4, A5, A6>> evaluateAsync(
          Jev.State state,
          Jev.Question<A1> question1,
          Jev.Question<A2> question2,
          Jev.Question<A3> question3,
          Jev.Question<A4> question4,
          Jev.Question<A5> question5,
          Jev.Question<A6> question6) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Prepared<A6> prepared6 = prepare(question6);
    return exchangeAsync(
        List.of(prepared1, prepared2, prepared3, prepared4, prepared5, prepared6),
        state,
        "question1",
        result ->
            new Evaluation6<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                prepared3.cast(result.answer().get(2)),
                prepared4.cast(result.answer().get(3)),
                prepared5.cast(result.answer().get(4)),
                prepared6.cast(result.answer().get(5)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function6<A1, A2, A3, A4, A5, A6, R> {
    R apply(A1 answer1, A2 answer2, A3 answer3, A4 answer4, A5 answer5, A6 answer6);
  }

  public record Evaluation6<A1, A2, A3, A4, A5, A6>(
      A1 answer1,
      A2 answer2,
      A3 answer3,
      A4 answer4,
      A5 answer5,
      A6 answer6,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation6(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        A4 answer4,
        A5 answer5,
        A6 answer6,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(
          answer1,
          answer2,
          answer3,
          answer4,
          answer5,
          answer6,
          model,
          usage,
          id,
          provider,
          Optional.empty());
    }

    public Evaluation6 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(answer3, "answer3");
      Objects.requireNonNull(answer4, "answer4");
      Objects.requireNonNull(answer5, "answer5");
      Objects.requireNonNull(answer6, "answer6");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(
        Function6<
                ? super A1, ? super A2, ? super A3, ? super A4, ? super A5, ? super A6, ? extends R>
            mapper) {
      return Objects.requireNonNull(mapper, "mapper")
          .apply(answer1, answer2, answer3, answer4, answer5, answer6);
    }
  }

  public <A1, A2, A3, A4, A5, A6, A7> Evaluation7<A1, A2, A3, A4, A5, A6, A7> evaluate(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5,
      Jev.Question<A6> question6,
      Jev.Question<A7> question7) {
    return evaluate(
        Jev.State.from(state),
        question1,
        question2,
        question3,
        question4,
        question5,
        question6,
        question7);
  }

  public <A1, A2, A3, A4, A5, A6, A7> Evaluation7<A1, A2, A3, A4, A5, A6, A7> evaluate(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5,
      Jev.Question<A6> question6,
      Jev.Question<A7> question7) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Prepared<A6> prepared6 = prepare(question6);
    Prepared<A7> prepared7 = prepare(question7);
    Evaluation<List<Object>> result =
        exchange(
            List.of(prepared1, prepared2, prepared3, prepared4, prepared5, prepared6, prepared7),
            state,
            "question1");
    return new Evaluation7<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        prepared3.cast(result.answer().get(2)),
        prepared4.cast(result.answer().get(3)),
        prepared5.cast(result.answer().get(4)),
        prepared6.cast(result.answer().get(5)),
        prepared7.cast(result.answer().get(6)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 7 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5, A6, A7>
      CompletableFuture<Evaluation7<A1, A2, A3, A4, A5, A6, A7>> evaluateAsync(
          String state,
          Jev.Question<A1> question1,
          Jev.Question<A2> question2,
          Jev.Question<A3> question3,
          Jev.Question<A4> question4,
          Jev.Question<A5> question5,
          Jev.Question<A6> question6,
          Jev.Question<A7> question7) {
    return evaluateAsync(
        Jev.State.from(state),
        question1,
        question2,
        question3,
        question4,
        question5,
        question6,
        question7);
  }

  /** Evaluates 7 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5, A6, A7>
      CompletableFuture<Evaluation7<A1, A2, A3, A4, A5, A6, A7>> evaluateAsync(
          Jev.State state,
          Jev.Question<A1> question1,
          Jev.Question<A2> question2,
          Jev.Question<A3> question3,
          Jev.Question<A4> question4,
          Jev.Question<A5> question5,
          Jev.Question<A6> question6,
          Jev.Question<A7> question7) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Prepared<A6> prepared6 = prepare(question6);
    Prepared<A7> prepared7 = prepare(question7);
    return exchangeAsync(
        List.of(prepared1, prepared2, prepared3, prepared4, prepared5, prepared6, prepared7),
        state,
        "question1",
        result ->
            new Evaluation7<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                prepared3.cast(result.answer().get(2)),
                prepared4.cast(result.answer().get(3)),
                prepared5.cast(result.answer().get(4)),
                prepared6.cast(result.answer().get(5)),
                prepared7.cast(result.answer().get(6)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function7<A1, A2, A3, A4, A5, A6, A7, R> {
    R apply(A1 answer1, A2 answer2, A3 answer3, A4 answer4, A5 answer5, A6 answer6, A7 answer7);
  }

  public record Evaluation7<A1, A2, A3, A4, A5, A6, A7>(
      A1 answer1,
      A2 answer2,
      A3 answer3,
      A4 answer4,
      A5 answer5,
      A6 answer6,
      A7 answer7,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation7(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        A4 answer4,
        A5 answer5,
        A6 answer6,
        A7 answer7,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(
          answer1,
          answer2,
          answer3,
          answer4,
          answer5,
          answer6,
          answer7,
          model,
          usage,
          id,
          provider,
          Optional.empty());
    }

    public Evaluation7 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(answer3, "answer3");
      Objects.requireNonNull(answer4, "answer4");
      Objects.requireNonNull(answer5, "answer5");
      Objects.requireNonNull(answer6, "answer6");
      Objects.requireNonNull(answer7, "answer7");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(
        Function7<
                ? super A1,
                ? super A2,
                ? super A3,
                ? super A4,
                ? super A5,
                ? super A6,
                ? super A7,
                ? extends R>
            mapper) {
      return Objects.requireNonNull(mapper, "mapper")
          .apply(answer1, answer2, answer3, answer4, answer5, answer6, answer7);
    }
  }

  public <A1, A2, A3, A4, A5, A6, A7, A8> Evaluation8<A1, A2, A3, A4, A5, A6, A7, A8> evaluate(
      String state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5,
      Jev.Question<A6> question6,
      Jev.Question<A7> question7,
      Jev.Question<A8> question8) {
    return evaluate(
        Jev.State.from(state),
        question1,
        question2,
        question3,
        question4,
        question5,
        question6,
        question7,
        question8);
  }

  public <A1, A2, A3, A4, A5, A6, A7, A8> Evaluation8<A1, A2, A3, A4, A5, A6, A7, A8> evaluate(
      Jev.State state,
      Jev.Question<A1> question1,
      Jev.Question<A2> question2,
      Jev.Question<A3> question3,
      Jev.Question<A4> question4,
      Jev.Question<A5> question5,
      Jev.Question<A6> question6,
      Jev.Question<A7> question7,
      Jev.Question<A8> question8) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Prepared<A6> prepared6 = prepare(question6);
    Prepared<A7> prepared7 = prepare(question7);
    Prepared<A8> prepared8 = prepare(question8);
    Evaluation<List<Object>> result =
        exchange(
            List.of(
                prepared1, prepared2, prepared3, prepared4, prepared5, prepared6, prepared7,
                prepared8),
            state,
            "question1");
    return new Evaluation8<>(
        prepared1.cast(result.answer().get(0)),
        prepared2.cast(result.answer().get(1)),
        prepared3.cast(result.answer().get(2)),
        prepared4.cast(result.answer().get(3)),
        prepared5.cast(result.answer().get(4)),
        prepared6.cast(result.answer().get(5)),
        prepared7.cast(result.answer().get(6)),
        prepared8.cast(result.answer().get(7)),
        result.model(),
        result.usage(),
        result.id(),
        result.provider(),
        result.requestId());
  }

  /** Evaluates 8 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5, A6, A7, A8>
      CompletableFuture<Evaluation8<A1, A2, A3, A4, A5, A6, A7, A8>> evaluateAsync(
          String state,
          Jev.Question<A1> question1,
          Jev.Question<A2> question2,
          Jev.Question<A3> question3,
          Jev.Question<A4> question4,
          Jev.Question<A5> question5,
          Jev.Question<A6> question6,
          Jev.Question<A7> question7,
          Jev.Question<A8> question8) {
    return evaluateAsync(
        Jev.State.from(state),
        question1,
        question2,
        question3,
        question4,
        question5,
        question6,
        question7,
        question8);
  }

  /** Evaluates 8 questions in one asynchronous request; cancel the original returned future. */
  public <A1, A2, A3, A4, A5, A6, A7, A8>
      CompletableFuture<Evaluation8<A1, A2, A3, A4, A5, A6, A7, A8>> evaluateAsync(
          Jev.State state,
          Jev.Question<A1> question1,
          Jev.Question<A2> question2,
          Jev.Question<A3> question3,
          Jev.Question<A4> question4,
          Jev.Question<A5> question5,
          Jev.Question<A6> question6,
          Jev.Question<A7> question7,
          Jev.Question<A8> question8) {
    Prepared<A1> prepared1 = prepare(question1);
    Prepared<A2> prepared2 = prepare(question2);
    Prepared<A3> prepared3 = prepare(question3);
    Prepared<A4> prepared4 = prepare(question4);
    Prepared<A5> prepared5 = prepare(question5);
    Prepared<A6> prepared6 = prepare(question6);
    Prepared<A7> prepared7 = prepare(question7);
    Prepared<A8> prepared8 = prepare(question8);
    return exchangeAsync(
        List.of(
            prepared1, prepared2, prepared3, prepared4, prepared5, prepared6, prepared7, prepared8),
        state,
        "question1",
        result ->
            new Evaluation8<>(
                prepared1.cast(result.answer().get(0)),
                prepared2.cast(result.answer().get(1)),
                prepared3.cast(result.answer().get(2)),
                prepared4.cast(result.answer().get(3)),
                prepared5.cast(result.answer().get(4)),
                prepared6.cast(result.answer().get(5)),
                prepared7.cast(result.answer().get(6)),
                prepared8.cast(result.answer().get(7)),
                result.model(),
                result.usage(),
                result.id(),
                result.provider(),
                result.requestId()));
  }

  @FunctionalInterface
  public interface Function8<A1, A2, A3, A4, A5, A6, A7, A8, R> {
    R apply(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        A4 answer4,
        A5 answer5,
        A6 answer6,
        A7 answer7,
        A8 answer8);
  }

  public record Evaluation8<A1, A2, A3, A4, A5, A6, A7, A8>(
      A1 answer1,
      A2 answer2,
      A3 answer3,
      A4 answer4,
      A5 answer5,
      A6 answer6,
      A7 answer7,
      A8 answer8,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation8(
        A1 answer1,
        A2 answer2,
        A3 answer3,
        A4 answer4,
        A5 answer5,
        A6 answer6,
        A7 answer7,
        A8 answer8,
        String model,
        Usage usage,
        Optional<String> id,
        Optional<String> provider) {
      this(
          answer1,
          answer2,
          answer3,
          answer4,
          answer5,
          answer6,
          answer7,
          answer8,
          model,
          usage,
          id,
          provider,
          Optional.empty());
    }

    public Evaluation8 {
      Objects.requireNonNull(answer1, "answer1");
      Objects.requireNonNull(answer2, "answer2");
      Objects.requireNonNull(answer3, "answer3");
      Objects.requireNonNull(answer4, "answer4");
      Objects.requireNonNull(answer5, "answer5");
      Objects.requireNonNull(answer6, "answer6");
      Objects.requireNonNull(answer7, "answer7");
      Objects.requireNonNull(answer8, "answer8");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }

    public <R> R map(
        Function8<
                ? super A1,
                ? super A2,
                ? super A3,
                ? super A4,
                ? super A5,
                ? super A6,
                ? super A7,
                ? super A8,
                ? extends R>
            mapper) {
      return Objects.requireNonNull(mapper, "mapper")
          .apply(answer1, answer2, answer3, answer4, answer5, answer6, answer7, answer8);
    }
  }

  // END GENERATED MULTI-QUESTION API

  private interface AnswerDecoder<T> {
    T decode(JsonNode answer);
  }

  public record Usage(long inputTokens, long outputTokens, OptionalDouble cost) {
    public Usage {
      Objects.requireNonNull(cost, "cost");
      if (inputTokens < 0 || outputTokens < 0)
        throw new IllegalArgumentException("token counts must be nonnegative");
      if (cost.isPresent() && !Double.isFinite(cost.getAsDouble()))
        throw new IllegalArgumentException("cost must be finite");
    }
  }

  public record Evaluation<T>(
      T answer,
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {
    public Evaluation(
        T answer, String model, Usage usage, Optional<String> id, Optional<String> provider) {
      this(answer, model, usage, id, provider, Optional.empty());
    }

    public Evaluation {
      Objects.requireNonNull(answer, "answer");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  public static final class Builder {
    private final String apiKey;
    private final URI baseUri;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;

    private Builder(String apiKey) {
      this(
          requireApiKey(apiKey),
          TYPESAFE_BASE_URI,
          "jev-latest",
          Duration.ofSeconds(30),
          HttpClient.newHttpClient());
    }

    private Builder(
        String apiKey, URI baseUri, String model, Duration timeout, HttpClient httpClient) {
      this.apiKey = apiKey;
      this.baseUri = baseUri;
      this.model = model;
      this.timeout = timeout;
      this.httpClient = httpClient;
    }

    public Builder typeSafe() {
      return baseUri(TYPESAFE_BASE_URI);
    }

    public Builder openRouter() {
      return baseUri(OPENROUTER_BASE_URI);
    }

    public Builder baseUri(URI value) {
      Objects.requireNonNull(value, "baseUri");
      String scheme = value.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          || value.getHost() == null
          || value.getHost().isBlank()
          || value.getUserInfo() != null
          || value.getQuery() != null
          || value.getFragment() != null)
        throw new IllegalArgumentException(
            "baseUri must use http or https with a host and no userinfo, query, or fragment");
      return new Builder(apiKey, value, model, timeout, httpClient);
    }

    public Builder model(String value) {
      return new Builder(apiKey, baseUri, requireText(value, "model"), timeout, httpClient);
    }

    public Builder timeout(Duration value) {
      Objects.requireNonNull(value, "timeout");
      if (value.isZero() || value.isNegative())
        throw new IllegalArgumentException("timeout must be positive");
      return new Builder(apiKey, baseUri, model, value, httpClient);
    }

    public Builder httpClient(HttpClient value) {
      return new Builder(
          apiKey, baseUri, model, timeout, Objects.requireNonNull(value, "httpClient"));
    }

    public JevEvaluator build() {
      return new JevEvaluator(this);
    }

    private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
    }

    private static String requireApiKey(String value) {
      requireText(value, "apiKey");
      if (!value.matches("[A-Za-z0-9\\-._~+/]+={0,}"))
        throw new IllegalArgumentException("apiKey contains unsupported characters");
      return value;
    }
  }
}
