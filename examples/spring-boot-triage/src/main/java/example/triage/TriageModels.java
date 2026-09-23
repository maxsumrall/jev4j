package example.triage;

import io.github.maxsumrall.jev4j.Jev;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

enum Category implements Jev.Described {
  BILLING("charges, payments, receipts, or refunds"),
  DELIVERY("shipping, pickup, missing items, or late orders"),
  OTHER("support requests outside billing and delivery");

  private final String description;

  Category(String description) {
    this.description = description;
  }

  @Override
  public String description() {
    return description;
  }
}

enum Urgency implements Jev.ScoreLevel {
  ROUTINE("can wait for normal support hours"),
  SOON("should be reviewed soon"),
  IMMEDIATE("needs immediate human attention");

  private final String description;

  Urgency(String description) {
    this.description = description;
  }

  @Override
  public String description() {
    return description;
  }
}

record TriageRequest(@NotBlank @Size(max = 1000) String message) {}

record TriageResponse(
    String queue,
    String decision,
    Category selectedCategory,
    Map<Category, Double> categoryProbabilities,
    double confidence,
    double safetyConcernProbability,
    double urgencyScore,
    Urgency urgency,
    String answerSource) {
  TriageResponse(
      String queue,
      String decision,
      Category selectedCategory,
      Map<Category, Double> categoryProbabilities,
      double confidence,
      double safetyConcernProbability,
      double urgencyScore,
      Urgency urgency,
      String answerSource) {
    this.queue = queue;
    this.decision = decision;
    this.selectedCategory = selectedCategory;
    this.categoryProbabilities = Map.copyOf(categoryProbabilities);
    this.confidence = confidence;
    this.safetyConcernProbability = safetyConcernProbability;
    this.urgencyScore = urgencyScore;
    this.urgency = urgency;
    this.answerSource = answerSource;
  }
}

record TriageAnswers(
    Jev.ChoiceAnswer<Category> category,
    Jev.NoulAnswer safetyConcern,
    Jev.EnumScoreAnswer<Urgency> urgency,
    String source) {}

record ApiError(String code, String message) {}
