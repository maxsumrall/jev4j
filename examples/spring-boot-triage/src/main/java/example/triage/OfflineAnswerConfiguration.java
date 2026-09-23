package example.triage;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("offline")
class OfflineAnswerConfiguration {
  @Bean
  TriageAnswerSource syntheticFixtureAnswerSource() {
    return message -> {
      Fixture fixture = FIXTURES.getOrDefault(message, UNCERTAIN);
      return new TriageAnswers(
          TriageQuestions.CATEGORY.answer(
              fixture.category(), fixture.categoryProbabilities(), fixture.confidence()),
          TriageQuestions.SAFETY.answer(fixture.safetyProbability()),
          TriageQuestions.URGENCY.answer(
              fixture.urgencyScore(), fixture.urgencyProbabilities(), fixture.confidence()),
          "synthetic-offline-fixture");
    };
  }

  private static final Fixture BILLING =
      fixture(Category.BILLING, 0.88, 0.06, 0.06, 0.93, 0.03, 0.2);
  private static final Fixture DELIVERY =
      fixture(Category.DELIVERY, 0.04, 0.91, 0.05, 0.95, 0.04, 0.8);
  private static final Fixture OTHER = fixture(Category.OTHER, 0.08, 0.07, 0.85, 0.90, 0.02, 0.3);
  private static final Fixture UNCERTAIN =
      fixture(Category.OTHER, 0.34, 0.32, 0.34, 0.41, 0.10, 1.0);
  private static final Map<String, Fixture> FIXTURES =
      Map.of(
          "I was charged twice for my groceries.", BILLING,
          "My delivery is late and has not arrived.", DELIVERY,
          "How do I update my loyalty card name?", OTHER);

  private static Fixture fixture(
      Category selected,
      double billing,
      double delivery,
      double other,
      double confidence,
      double safety,
      double urgency) {
    return new Fixture(
        selected,
        Map.of(Category.BILLING, billing, Category.DELIVERY, delivery, Category.OTHER, other),
        confidence,
        safety,
        urgency,
        Map.of(Urgency.ROUTINE, 1.0 - urgency, Urgency.SOON, urgency, Urgency.IMMEDIATE, 0.0));
  }

  private record Fixture(
      Category category,
      Map<Category, Double> categoryProbabilities,
      double confidence,
      double safetyProbability,
      double urgencyScore,
      Map<Urgency, Double> urgencyProbabilities) {
    Fixture(
        Category category,
        Map<Category, Double> categoryProbabilities,
        double confidence,
        double safetyProbability,
        double urgencyScore,
        Map<Urgency, Double> urgencyProbabilities) {
      this.category = category;
      this.categoryProbabilities = Map.copyOf(categoryProbabilities);
      this.confidence = confidence;
      this.safetyProbability = safetyProbability;
      this.urgencyScore = urgencyScore;
      this.urgencyProbabilities = Map.copyOf(urgencyProbabilities);
    }
  }
}
