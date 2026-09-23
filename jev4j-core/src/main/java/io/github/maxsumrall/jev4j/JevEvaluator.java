package io.github.maxsumrall.jev4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Immutable, thread-safe HTTP client for evaluating one Jev question at a time. */
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
    Objects.requireNonNull(question, "question");
    return exchange(
        questionNode("noul", question.instructions(), noulCriteria(question.descriptions())),
        state,
        answer -> {
          requireType(answer, "noul");
          return question.answer(requiredProbability(answer, "noul"));
        });
  }

  public <E extends Enum<E>> Evaluation<Jev.ChoiceAnswer<E>> evaluateWithMetadata(
      String state, Jev.ChoiceQuestion<E> question) {
    Objects.requireNonNull(question, "question");
    ObjectNode criteria = JSON.createObjectNode();
    question.descriptions().forEach((key, value) -> criteria.put(key.name(), value));
    return exchange(
        questionNode("choice", question.instructions(), criteria),
        state,
        answer -> {
          requireType(answer, "choice");
          String selected = requiredText(answer, "choice");
          E value;
          try {
            value = Enum.valueOf(question.optionType(), selected);
          } catch (IllegalArgumentException e) {
            throw malformed("choice is not one of the declared options");
          }
          Map<E, Double> probabilities = enumProbabilities(answer, question.optionType());
          return question.answer(value, probabilities, requiredProbability(answer, "confidence"));
        });
  }

  public <E extends Enum<E>> Evaluation<Jev.EnumScoreAnswer<E>> evaluateWithMetadata(
      String state, Jev.EnumScoreQuestion<E> question) {
    Objects.requireNonNull(question, "question");
    ArrayNode criteria = JSON.createArrayNode();
    for (E level : question.levelType().getEnumConstants())
      criteria.add(question.descriptions().get(level));
    return exchange(
        questionNode("score", question.instructions(), criteria),
        state,
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
        });
  }

  public Evaluation<Jev.ScoreAnswer> evaluateWithMetadata(
      String state, Jev.ScoreQuestion question) {
    Objects.requireNonNull(question, "question");
    ArrayNode criteria = JSON.createArrayNode();
    question.levels().forEach(criteria::add);
    return exchange(
        questionNode("score", question.instructions(), criteria),
        state,
        answer -> {
          requireType(answer, "score");
          return question.answer(
              requiredNumber(answer, "score"),
              indexedProbabilities(answer, question.levels().size()),
              requiredProbability(answer, "confidence"));
        });
  }

  private <T> Evaluation<T> exchange(ObjectNode question, String state, AnswerDecoder<T> decoder) {
    Objects.requireNonNull(state, "state");
    ObjectNode root = JSON.createObjectNode();
    root.put("state", state);
    root.put("model", model);
    root.putObject("questions").set(QUESTION_KEY, question);
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(root)))
            .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JevEvaluationException("evaluation interrupted", e);
    } catch (IOException e) {
      throw new JevEvaluationException("evaluation request failed", e);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new JevEvaluationException(
          "evaluation failed with HTTP status " + response.statusCode(), response.statusCode());
    try {
      JsonNode body = JSON.readTree(response.body());
      if (body == null || !body.isObject()) throw malformed("response must be a JSON object");
      JsonNode answers = body.get("answers");
      if (answers == null || !answers.isObject()) throw malformed("missing object 'answers'");
      JsonNode answer = answers.get(QUESTION_KEY);
      if (answer == null || !answer.isObject())
        throw malformed("missing answer for '" + QUESTION_KEY + "'");
      T value = decoder.decode(answer);
      String responseModel = requiredText(body, "model");
      JsonNode usageNode = body.get("usage");
      if (usageNode == null || !usageNode.isObject()) throw malformed("missing object 'usage'");
      Usage usage =
          new Usage(
              requiredLong(usageNode, "input_tokens"),
              requiredLong(usageNode, "output_tokens"),
              optionalFinite(usageNode, "cost"));
      return new Evaluation<>(
          value, responseModel, usage, optionalText(body, "id"), optionalText(body, "provider"));
    } catch (JacksonException e) {
      // Jackson's exception and cause messages can quote arbitrary response content.
      throw malformed("invalid JSON");
    } catch (IllegalArgumentException e) {
      throw malformed("invalid evaluation response");
    }
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
      T answer, String model, Usage usage, Optional<String> id, Optional<String> provider) {
    public Evaluation {
      Objects.requireNonNull(answer, "answer");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
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
