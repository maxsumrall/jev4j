package example.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("synthetic")
class TriageServiceTest {
  @Test
  void routesAtConfidenceBoundaryAndReviewsImmediatelyBelowIt() {
    assertEquals("ROUTE", serviceWithConfidence(0.70).triage("message").decision());
    assertEquals(
        "MANUAL_REVIEW_LOW_CONFIDENCE",
        serviceWithConfidence(Math.nextDown(0.70)).triage("message").decision());
  }

  @Test
  void responseDefensivelyCopiesCategoryProbabilities() {
    var probabilities = new EnumMap<Category, Double>(Category.class);
    probabilities.putAll(
        Map.of(Category.BILLING, 0.8, Category.DELIVERY, 0.1, Category.OTHER, 0.1));
    var response =
        new TriageResponse(
            "queue", "decision", Category.BILLING, probabilities, 1, 0, 0, Urgency.ROUTINE, "test");

    probabilities.put(Category.BILLING, 0.1);
    assertEquals(0.8, response.categoryProbabilities().get(Category.BILLING));
    assertThrows(
        UnsupportedOperationException.class,
        () -> response.categoryProbabilities().put(Category.BILLING, 0.2));
  }

  private static TriageService serviceWithConfidence(double confidence) {
    return new TriageService(
        message ->
            new TriageAnswers(
                TriageQuestions.CATEGORY.answer(
                    Category.BILLING,
                    Map.of(Category.BILLING, 0.8, Category.DELIVERY, 0.1, Category.OTHER, 0.1),
                    confidence),
                TriageQuestions.SAFETY.answer(0),
                TriageQuestions.URGENCY.answer(
                    0, Map.of(Urgency.ROUTINE, 1.0, Urgency.SOON, 0.0, Urgency.IMMEDIATE, 0.0), 1),
                "test"));
  }
}
