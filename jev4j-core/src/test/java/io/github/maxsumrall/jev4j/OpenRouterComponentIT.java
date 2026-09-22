package io.github.maxsumrall.jev4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Three bounded, real OpenRouter requests. Run only with the openrouter-component profile. */
final class OpenRouterComponentIT {
  private enum Route {
    BILLING,
    SUPPORT
  }

  private enum Quality implements Jev.ScoreLevel {
    POOR("incorrect or unhelpful"),
    ACCEPTABLE("mostly correct and useful"),
    EXCELLENT("fully correct, concise, and useful");

    private final String description;

    Quality(String description) {
      this.description = description;
    }

    @Override
    public String description() {
      return description;
    }
  }

  private static String apiKey;

  @BeforeAll
  static void requireCredentials() {
    apiKey = System.getenv("OPENROUTER_API_KEY");
    assertFalse(
        apiKey == null || apiKey.isBlank(),
        "OPENROUTER_API_KEY must be set when -Popenrouter-component is enabled");
  }

  @Test
  void evaluatesThreePrimitiveQuestionTypesAgainstOpenRouter() {
    var evaluator =
        JevEvaluator.builder(apiKey)
            .openRouter()
            .model("jev-latest")
            .timeout(Duration.ofSeconds(30))
            .build();

    var noul =
        evaluator.evaluateWithMetadata(
            Jev.noul("Is the synthetic input explicitly about a duplicate charge?"),
            "Synthetic test input: my card shows the same grocery charge twice.");
    assertProbability(noul.answer().probabilityTrue());
    assertMetadata(noul);

    var choice =
        evaluator.evaluateWithMetadata(
            Jev.choice(Route.class, "Choose the best route for this synthetic request"),
            "Synthetic test input: please explain an unfamiliar invoice fee.");
    assertTrue(
        choice.answer().value() == Route.BILLING || choice.answer().value() == Route.SUPPORT);
    assertEquals(Route.values().length, choice.answer().probabilities().size());
    choice.answer().probabilities().values().forEach(OpenRouterComponentIT::assertProbability);
    assertProbability(choice.answer().confidence());
    assertMetadata(choice);

    var score =
        evaluator.evaluateWithMetadata(
            Jev.score(Quality.class, "Score the quality of this synthetic response"),
            "Question: What is 2 + 2? Response: 4.");
    assertTrue(Double.isFinite(score.answer().value()));
    assertTrue(score.answer().value() >= 0 && score.answer().value() <= 2);
    assertEquals(Quality.values().length, score.answer().probabilities().size());
    assertEquals(
        Map.of(Quality.POOR, 0, Quality.ACCEPTABLE, 1, Quality.EXCELLENT, 2).keySet(),
        score.answer().probabilities().keySet());
    score.answer().probabilities().values().forEach(OpenRouterComponentIT::assertProbability);
    assertProbability(score.answer().confidence());
    assertMetadata(score);
  }

  private static void assertProbability(double value) {
    assertTrue(Double.isFinite(value) && value >= 0 && value <= 1);
  }

  private static void assertMetadata(JevEvaluator.Evaluation<?> evaluation) {
    assertFalse(evaluation.model().isBlank());
    assertTrue(evaluation.usage().inputTokens() >= 0);
    assertTrue(evaluation.usage().outputTokens() >= 0);
    evaluation.usage().cost().ifPresent(cost -> assertTrue(Double.isFinite(cost)));
  }
}
