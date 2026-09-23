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
  void responseDefensivelyCopiesCategoryProbabilities() {
    Map<Category, Double> probabilities = new EnumMap<>(Category.class);
    probabilities.putAll(
        Map.of(Category.BILLING, 0.8, Category.DELIVERY, 0.1, Category.OTHER, 0.1));
    TriageResponse response =
        new TriageResponse(
            "queue", "decision", Category.BILLING, probabilities, 1, 0, 0, Urgency.ROUTINE, "test");

    probabilities.put(Category.BILLING, 0.1);
    assertEquals(0.8, response.categoryProbabilities().get(Category.BILLING));
    assertThrows(
        UnsupportedOperationException.class,
        () -> response.categoryProbabilities().put(Category.BILLING, 0.2));
  }
}
