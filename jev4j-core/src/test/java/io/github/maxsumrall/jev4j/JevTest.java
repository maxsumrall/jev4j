package io.github.maxsumrall.jev4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JevTest {
  private enum Weather implements Jev.Described {
    SUN("sunny"),
    RAIN("rainy");

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
    MEDIUM("medium"),
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

  private enum Other {
    UNKNOWN
  }

  @Test
  void noulTypesAndInclusiveThresholdAreExecutableContracts() {
    Jev.NoulQuestion plainQuestion = Jev.noul("rain").describe(true, "It rains");
    Jev.NoulAnswer plain = plainQuestion.answer(0.6);
    assertEquals(0.6, plain.probabilityTrue());
    assertTrue(plain.isTrueAt(0.6));
    assertFalse(plain.isTrueAt(0.61));

    Jev.ThresholdNoulQuestion configured = plainQuestion.threshold(0.6);
    Jev.ThresholdNoulAnswer equal = configured.answer(0.6);
    assertTrue(equal.isTrue());
    assertFalse(configured.answer(0.59).isTrue());
    assertTrue(configured.answer(0.61).isTrue());
    assertEquals(0.6, equal.threshold());
    assertFalse(equal.isTrueAt(0.61));
    assertTrue(equal.isTrueAt(0.6));
    assertEquals(0.6, equal.threshold());
    assertTrue(equal.isTrue());
    assertThrows(IllegalArgumentException.class, () -> equal.isTrueAt(Double.POSITIVE_INFINITY));
  }

  @Test
  void derivedQuestionsDoNotMutateOriginals() {
    var original = Jev.choice(Weather.class, "weather");
    var configured =
        original.describe(Weather.SUN, "clear sky").minConfidence(0.8).minProbability(0.7);

    assertEquals("sunny", original.descriptions().get(Weather.SUN));
    assertEquals(0, original.minConfidence());
    assertEquals("clear sky", configured.descriptions().get(Weather.SUN));
    assertEquals("weather", original.instructions());
    assertThrows(
        UnsupportedOperationException.class,
        () -> configured.descriptions().put(Weather.SUN, "mutated"));
  }

  @Test
  void choiceCopiesAsymmetricDistributionAndAppliesBothPoliciesInclusively() {
    var probabilities = new EnumMap<Weather, Double>(Weather.class);
    probabilities.put(Weather.SUN, 0.8);
    probabilities.put(Weather.RAIN, 0.2);
    var question = Jev.choice(Weather.class, "weather").minConfidence(0.7).minProbability(0.8);
    var accepted = question.answer(Weather.SUN, probabilities, 0.7);

    probabilities.put(Weather.SUN, 0.1);
    assertEquals(0.8, accepted.probabilities().get(Weather.SUN));
    assertTrue(accepted.meetsThresholds());
    assertEquals(Weather.SUN, accepted.acceptedValue().orElseThrow());
    assertFalse(
        question
            .answer(Weather.SUN, Map.of(Weather.SUN, 0.79, Weather.RAIN, 0.21), 0.9)
            .meetsThresholds());
    assertFalse(
        question
            .answer(Weather.SUN, Map.of(Weather.SUN, 0.9, Weather.RAIN, 0.1), 0.69)
            .meetsThresholds());
    assertTrue(
        Jev.choice(Weather.class, "weather")
            .answer(Weather.RAIN, Map.of(Weather.SUN, 0.4, Weather.RAIN, 0.6), 0)
            .meetsThresholds());
    assertThrows(
        UnsupportedOperationException.class, () -> accepted.probabilities().put(Weather.SUN, 0.1));
  }

  @Test
  void invalidOrUnsupportedAnswerDataFails() throws ReflectiveOperationException {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Jev.choice(Weather.class, "weather").answer(Weather.SUN, Map.of(Weather.SUN, 1.0), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Jev.ChoiceAnswer<>(
                Weather.class, Weather.SUN, Map.of(Weather.SUN, 1.0, Weather.RAIN, -0.1), 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> Jev.noul("x").threshold(Double.NaN));
    assertThrows(
        IllegalArgumentException.class, () -> new Jev.NoulAnswer(Double.NEGATIVE_INFINITY));
    assertThrows(
        IllegalArgumentException.class, () -> new Jev.NoulQuestion("x", Map.of(true, " ")));
    assertThrows(
        IllegalArgumentException.class, () -> new Jev.ScoreQuestion("x", List.of("low", " "), 0));

    // Generic callers cannot express this mismatch; reflection verifies the DTO boundary also
    // rejects it.
    var constructor =
        Jev.ChoiceAnswer.class.getConstructor(
            Class.class, Enum.class, Map.class, double.class, double.class, double.class);
    var failure =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () ->
                constructor.newInstance(
                    Weather.class,
                    Other.UNKNOWN,
                    Map.of(Weather.SUN, 0.5, Weather.RAIN, 0.5),
                    1,
                    0,
                    0));
    assertTrue(failure.getCause() instanceof IllegalArgumentException);
  }

  @Test
  void enumScoreUsesOrderAndRoundsExactMidpointUp() {
    var probabilities = Map.of(Size.SMALL, 0.1, Size.MEDIUM, 0.7, Size.LARGE, 0.2);
    var question = Jev.score(Size.class, "size").minConfidence(0.8);

    assertEquals(Size.MEDIUM, question.answer(0.5, probabilities, 0.8).nearestLevel());
    assertEquals(Size.LARGE, question.answer(1.5, probabilities, 0.8).nearestLevel());
    assertEquals(Size.MEDIUM, question.answer(1.49, probabilities, 0.8).nearestLevel());
    // At this DTO boundary, the raw score is independent data and is not cross-checked against
    // the probability distribution's mean.
    var fractional = question.answer(1.2, probabilities, 0.8);
    assertEquals(1.2, fractional.acceptedValue().orElseThrow());
    String result =
        switch (fractional.nearestLevel()) {
          case SMALL -> "small";
          case MEDIUM -> "medium";
          case LARGE -> "large";
        };
    assertEquals("medium", result);
    assertTrue(question.answer(1.2, probabilities, 0.79).acceptedValue().isEmpty());
    assertThrows(
        UnsupportedOperationException.class, () -> fractional.probabilities().put(Size.SMALL, 0.2));
    assertThrows(IllegalArgumentException.class, () -> question.answer(2.01, probabilities, 0.8));
  }

  @Test
  void plainScoreBuilderIsImmutableAndEnforcesLimits() {
    var one = Jev.score("quality").level("bad");
    var two = one.level("good");
    assertEquals(1, one.levels().size());
    assertThrows(IllegalArgumentException.class, one::build);

    var question = two.build().minConfidence(0.5);
    var source = new java.util.ArrayList<>(List.of(0.2, 0.8));
    var answer = question.answer(0.75, source, 0.5);
    source.set(1, 0.1);
    assertEquals(0.8, answer.probabilities().get(1));
    assertEquals(0.75, answer.acceptedValue().orElseThrow());
    assertTrue(question.answer(0.75, List.of(0.2, 0.8), 0.49).acceptedValue().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> answer.probabilities().set(0, 0.5));
    assertThrows(UnsupportedOperationException.class, () -> question.levels().set(0, "awful"));
    assertThrows(
        IllegalArgumentException.class,
        () -> question.answer(Double.POSITIVE_INFINITY, List.of(0.2, 0.8), 0.5));

    var tooMany = Jev.score("too many");
    for (int index = 0; index < 11; index++) {
      tooMany = tooMany.level("level " + index);
    }
    var invalid = tooMany;
    assertThrows(IllegalArgumentException.class, invalid::build);
    assertThrows(
        IllegalArgumentException.class,
        () -> Jev.score("duplicate").level("same").level("same").build());
  }

  @Test
  void readmeChoiceAndScoreExamplesCompile() {
    var choice =
        Jev.choice(Weather.class, "Choose the expected weather")
            .answer(Weather.RAIN, Map.of(Weather.SUN, 0.4, Weather.RAIN, 0.6), 0.9);
    var score =
        Jev.score("Rate the result")
            .level("poor")
            .level("excellent")
            .build()
            .answer(0.75, List.of(0.2, 0.8), 0.9);

    assertEquals(Weather.RAIN, choice.acceptedValue().orElseThrow());
    assertEquals(0.75, score.acceptedValue().orElseThrow());
  }
}
