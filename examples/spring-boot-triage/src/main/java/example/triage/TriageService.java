package example.triage;

class TriageService {
  private final TriageAnswerSource answerSource;

  TriageService(TriageAnswerSource answerSource) {
    this.answerSource = answerSource;
  }

  TriageResponse triage(String message) {
    TriageAnswers answers = answerSource.evaluate(message);
    var category = answers.category();
    boolean review = !category.meetsThresholds();
    String queue = review ? "MANUAL_REVIEW" : queueFor(category.value());
    return new TriageResponse(
        queue,
        review ? "MANUAL_REVIEW_LOW_CONFIDENCE" : "ROUTE",
        category.value(),
        category.probabilities(),
        category.confidence(),
        answers.safetyConcern().probabilityTrue(),
        answers.urgency().value(),
        answers.urgency().nearestLevel(),
        answers.source());
  }

  private static String queueFor(Category category) {
    return switch (category) {
      case BILLING -> "BILLING_SUPPORT";
      case DELIVERY -> "DELIVERY_SUPPORT";
      case OTHER -> "GENERAL_SUPPORT";
    };
  }
}
