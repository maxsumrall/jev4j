package example;

import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluator;
import java.util.Map;

/** A framework-free Jev example with offline and explicitly enabled live modes. */
public final class PlainJavaExample {
  private static final Jev.ThresholdNoulQuestion REFUND =
      Jev.noul("Does this message require a refund?")
          .describe(true, "A refund should be considered")
          .describe(false, "No refund is needed")
          .threshold(0.70);

  private static final Jev.ChoiceQuestion<Destination> DESTINATION =
      Jev.choice(Destination.class, "Route this customer message")
          .minConfidence(0.70)
          .minProbability(0.60);

  private static final Jev.EnumScoreQuestion<Urgency> URGENCY =
      Jev.score(Urgency.class, "Score the urgency").minConfidence(0.65);

  private PlainJavaExample() {}

  enum Destination {
    BILLING,
    SUPPORT
  }

  enum Urgency implements Jev.ScoreLevel {
    LOW("Can wait for normal handling"),
    MEDIUM("Should be handled soon"),
    HIGH("Needs immediate attention");

    private final String description;

    Urgency(String description) {
      this.description = description;
    }

    @Override
    public String description() {
      return description;
    }
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      runSynthetic();
      return;
    }
    if (!"--live".equals(args[0]) || args.length > 2) {
      throw new IllegalArgumentException("usage: [--live [openrouter-model]]");
    }
    runLive(args.length == 2 ? args[1] : "jev-latest");
  }

  private static void runSynthetic() {
    System.out.println("SYNTHETIC LOCAL ANSWERS (not model output; no network call)");

    Jev.ThresholdNoulAnswer refund = REFUND.answer(0.74);
    System.out.printf(
        "refund: configured=%s, stricter override=%s%n", refund.isTrue(), refund.isTrueAt(0.80));

    Jev.ChoiceAnswer<Destination> destination =
        DESTINATION.answer(
            Destination.BILLING,
            Map.of(Destination.BILLING, 0.78, Destination.SUPPORT, 0.22),
            0.88);
    System.out.println("route: " + route(destination));

    Jev.EnumScoreAnswer<Urgency> urgency =
        URGENCY.answer(
            1.5, Map.of(Urgency.LOW, 0.10, Urgency.MEDIUM, 0.40, Urgency.HIGH, 0.50), 0.90);
    System.out.println("urgency: " + urgencyRoute(urgency));
  }

  private static void runLive(String model) {
    String apiKey = System.getenv("OPENROUTER_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("OPENROUTER_API_KEY must be set for --live");
    }
    // The key is passed directly to the client and is never printed.
    JevEvaluator evaluator = JevEvaluator.builder(apiKey).openRouter().model(model).build();
    String state = "I was charged twice and need this fixed today.";
    System.out.println("LIVE OpenRouter model output");
    System.out.println("refund: " + evaluator.evaluate(REFUND, state).isTrue());
    System.out.println("route: " + route(evaluator.evaluate(DESTINATION, state)));
    System.out.println("urgency: " + urgencyRoute(evaluator.evaluate(URGENCY, state)));
  }

  static String route(Jev.ChoiceAnswer<Destination> answer) {
    if (!answer.meetsThresholds()) return "REVIEW";
    return switch (answer.value()) {
      case BILLING -> "ACCEPTED:BILLING";
      case SUPPORT -> "ACCEPTED:SUPPORT";
    };
  }

  static String urgencyRoute(Jev.EnumScoreAnswer<Urgency> answer) {
    if (!answer.meetsThresholds()) return "REVIEW_LOW_CONFIDENCE";
    return switch (answer.nearestLevel()) {
      case LOW -> "NORMAL_QUEUE";
      case MEDIUM -> "PRIORITY_QUEUE";
      case HIGH -> "IMMEDIATE_QUEUE";
    };
  }
}
