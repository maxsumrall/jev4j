package io.github.maxsumrall.jev4j;

import com.google.errorprone.annotations.Var;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Entry point and immutable contracts for Jev questions and answers. */
public final class Jev {
  public static final int MAX_CHOICES = 255;
  private static final double DISTRIBUTION_SUM_TOLERANCE = 1e-6;

  private Jev() {}

  public interface Described {
    String description();
  }

  public interface ScoreLevel extends Described {}

  /**
   * Declares a Noul without a decision threshold; callers choose one with {@link
   * NoulAnswer#isTrueAt}.
   */
  public static NoulQuestion noul(String instructions) {
    return new NoulQuestion(text(instructions, "instructions"), Map.of());
  }

  public static <E extends Enum<E>> ChoiceQuestion<E> choice(
      Class<E> optionType, String instructions) {
    return new ChoiceQuestion<>(optionType, instructions, Map.of(), 0, 0);
  }

  /** Declares a score whose numeric levels follow enum declaration order, starting at zero. */
  public static <E extends Enum<E>> EnumScoreQuestion<E> score(
      Class<E> levelType, String instructions) {
    return new EnumScoreQuestion<>(levelType, instructions, Map.of(), 0);
  }

  /** Declares a score whose numeric levels follow calls to {@link ScoreBuilder#level(String)}. */
  public static ScoreBuilder score(String instructions) {
    return new ScoreBuilder(text(instructions, "instructions"), List.of());
  }

  public record NoulQuestion(String instructions, Map<Boolean, String> descriptions) {
    public NoulQuestion(String instructions, Map<Boolean, String> descriptions) {
      this.instructions = text(instructions, "instructions");
      this.descriptions = validDescriptions(descriptions);
    }

    public NoulQuestion describe(boolean value, String description) {
      Map<Boolean, String> copy = new LinkedHashMap<>(descriptions);
      copy.put(value, text(description, "description"));
      return new NoulQuestion(instructions, copy);
    }

    /** Sets the inclusive decision threshold in {@code [0, 1]}. */
    public ThresholdNoulQuestion threshold(double threshold) {
      return new ThresholdNoulQuestion(
          instructions, descriptions, probability(threshold, "threshold"));
    }

    /** Constructs local answer data; it does not contact or evaluate a remote service. */
    public NoulAnswer answer(double probabilityTrue) {
      return new NoulAnswer(probabilityTrue);
    }
  }

  public record ThresholdNoulQuestion(
      String instructions, Map<Boolean, String> descriptions, double threshold) {
    public ThresholdNoulQuestion(
        String instructions, Map<Boolean, String> descriptions, double threshold) {
      this.instructions = text(instructions, "instructions");
      this.descriptions = validDescriptions(descriptions);
      this.threshold = probability(threshold, "threshold");
    }

    public ThresholdNoulQuestion describe(boolean value, String description) {
      Map<Boolean, String> copy = new LinkedHashMap<>(descriptions);
      copy.put(value, text(description, "description"));
      return new ThresholdNoulQuestion(instructions, copy, threshold);
    }

    public ThresholdNoulQuestion threshold(double value) {
      return new ThresholdNoulQuestion(instructions, descriptions, value);
    }

    /** Constructs local answer data; it does not contact or evaluate a remote service. */
    public ThresholdNoulAnswer answer(double probabilityTrue) {
      return new ThresholdNoulAnswer(probabilityTrue, threshold);
    }
  }

  public record NoulAnswer(double probabilityTrue) {
    public NoulAnswer(double probabilityTrue) {
      this.probabilityTrue = probability(probabilityTrue, "probabilityTrue");
    }

    /** Applies an inclusive threshold in {@code [0, 1]}. */
    public boolean isTrueAt(double threshold) {
      return probabilityTrue >= probability(threshold, "threshold");
    }
  }

  public record ThresholdNoulAnswer(double probabilityTrue, double threshold) {
    public ThresholdNoulAnswer(double probabilityTrue, double threshold) {
      this.probabilityTrue = probability(probabilityTrue, "probabilityTrue");
      this.threshold = probability(threshold, "threshold");
    }

    /** Applies the question's configured inclusive threshold. */
    public boolean isTrue() {
      return probabilityTrue >= threshold;
    }

    /** Applies an inclusive override threshold in {@code [0, 1]} without changing this answer. */
    public boolean isTrueAt(double threshold) {
      return probabilityTrue >= probability(threshold, "threshold");
    }
  }

  public static final class ChoiceQuestion<E extends Enum<E>> {
    private final Class<E> optionType;
    private final String instructions;
    private final Map<E, String> descriptions;
    private final double minConfidence;
    private final double minProbability;

    private ChoiceQuestion(
        Class<E> optionType,
        String instructions,
        Map<E, String> descriptions,
        double minConfidence,
        double minProbability) {
      this.optionType = enumType(optionType, 1, MAX_CHOICES);
      this.instructions = text(instructions, "instructions");
      Map<E, String> copy = new EnumMap<>(optionType);
      for (E option : optionType.getEnumConstants()) {
        copy.put(
            option,
            option instanceof Described d ? text(d.description(), "description") : option.name());
      }
      descriptions.forEach(
          (key, value) -> copy.put(member(optionType, key), text(value, "description")));
      this.descriptions = Collections.unmodifiableMap(copy);
      this.minConfidence = probability(minConfidence, "minConfidence");
      this.minProbability = probability(minProbability, "minProbability");
    }

    public ChoiceQuestion<E> describe(E option, String description) {
      Map<E, String> copy = new EnumMap<>(descriptions);
      copy.put(member(optionType, option), text(description, "description"));
      return new ChoiceQuestion<>(optionType, instructions, copy, minConfidence, minProbability);
    }

    public ChoiceQuestion<E> minConfidence(double value) {
      return new ChoiceQuestion<>(optionType, instructions, descriptions, value, minProbability);
    }

    public ChoiceQuestion<E> minProbability(double value) {
      return new ChoiceQuestion<>(optionType, instructions, descriptions, minConfidence, value);
    }

    public String instructions() {
      return instructions;
    }

    public Class<E> optionType() {
      return optionType;
    }

    public Map<E, String> descriptions() {
      return descriptions;
    }

    public double minConfidence() {
      return minConfidence;
    }

    public double minProbability() {
      return minProbability;
    }

    /** Constructs local answer data; it does not contact or evaluate a remote service. */
    public ChoiceAnswer<E> answer(E value, Map<E, Double> probabilities, double confidence) {
      return new ChoiceAnswer<>(
          optionType, value, probabilities, confidence, minConfidence, minProbability);
    }
  }

  public static final class ChoiceAnswer<E extends Enum<E>> {
    private final E value;
    private final Map<E, Double> probabilities;
    private final double confidence;
    private final boolean meetsThresholds;

    public ChoiceAnswer(
        Class<E> optionType,
        E value,
        Map<E, Double> probabilities,
        double confidence,
        double minConfidence,
        double minProbability) {
      enumType(optionType, 1, MAX_CHOICES);
      this.value = member(optionType, value);
      this.probabilities = distribution(optionType, probabilities);
      this.confidence = probability(confidence, "confidence");
      double requiredConfidence = probability(minConfidence, "minConfidence");
      double requiredProbability = probability(minProbability, "minProbability");
      this.meetsThresholds =
          confidence >= requiredConfidence
              && Objects.requireNonNull(this.probabilities.get(value)) >= requiredProbability;
    }

    public E value() {
      return value;
    }

    public Map<E, Double> probabilities() {
      return probabilities;
    }

    public double confidence() {
      return confidence;
    }

    public boolean meetsThresholds() {
      return meetsThresholds;
    }

    public Optional<E> acceptedValue() {
      return meetsThresholds ? Optional.of(value) : Optional.empty();
    }
  }

  public static final class EnumScoreQuestion<E extends Enum<E>> {
    private final Class<E> levelType;
    private final String instructions;
    private final Map<E, String> descriptions;
    private final double minConfidence;

    private EnumScoreQuestion(
        Class<E> levelType,
        String instructions,
        Map<E, String> descriptions,
        double minConfidence) {
      this.levelType = enumType(levelType, 2, 10);
      this.instructions = text(instructions, "instructions");
      Map<E, String> copy = new EnumMap<>(levelType);
      for (E level : levelType.getEnumConstants()) {
        copy.put(
            level,
            level instanceof ScoreLevel d ? text(d.description(), "description") : level.name());
      }
      descriptions.forEach(
          (key, value) -> copy.put(member(levelType, key), text(value, "description")));
      this.descriptions = Collections.unmodifiableMap(copy);
      this.minConfidence = probability(minConfidence, "minConfidence");
    }

    public EnumScoreQuestion<E> describe(E level, String description) {
      Map<E, String> copy = new EnumMap<>(descriptions);
      copy.put(member(levelType, level), text(description, "description"));
      return new EnumScoreQuestion<>(levelType, instructions, copy, minConfidence);
    }

    public EnumScoreQuestion<E> minConfidence(double value) {
      return new EnumScoreQuestion<>(levelType, instructions, descriptions, value);
    }

    public Class<E> levelType() {
      return levelType;
    }

    public String instructions() {
      return instructions;
    }

    public Map<E, String> descriptions() {
      return descriptions;
    }

    public double minConfidence() {
      return minConfidence;
    }

    /** Constructs local answer data; it does not contact or evaluate a remote service. */
    public EnumScoreAnswer<E> answer(
        double value, Map<E, Double> probabilities, double confidence) {
      return new EnumScoreAnswer<>(levelType, value, probabilities, confidence, minConfidence);
    }
  }

  public static final class EnumScoreAnswer<E extends Enum<E>> {
    private final double value;
    private final Map<E, Double> probabilities;
    private final double confidence;
    private final E nearestLevel;
    private final boolean meetsThresholds;

    public EnumScoreAnswer(
        Class<E> levelType,
        double value,
        Map<E, Double> probabilities,
        double confidence,
        double minConfidence) {
      E[] levels = enumType(levelType, 2, 10).getEnumConstants();
      this.value = range(value, 0, levels.length - 1, "value");
      this.probabilities = distribution(levelType, probabilities);
      this.confidence = probability(confidence, "confidence");
      this.nearestLevel = levels[(int) Math.floor(value + 0.5)];
      this.meetsThresholds = confidence >= probability(minConfidence, "minConfidence");
    }

    public double value() {
      return value;
    }

    public Map<E, Double> probabilities() {
      return probabilities;
    }

    public double confidence() {
      return confidence;
    }

    /** Converts to the nearest declared level; exact midpoints round toward the higher level. */
    public E nearestLevel() {
      return nearestLevel;
    }

    public boolean meetsThresholds() {
      return meetsThresholds;
    }

    /** Returns the accepted raw score; use {@link #nearestLevel()} for explicit enum conversion. */
    public OptionalDouble acceptedValue() {
      return meetsThresholds ? OptionalDouble.of(value) : OptionalDouble.empty();
    }
  }

  public record ScoreBuilder(String instructions, List<String> levels) {
    public ScoreBuilder(String instructions, List<String> levels) {
      this.instructions = text(instructions, "instructions");
      this.levels = List.copyOf(levels);
      this.levels.forEach(level -> text(level, "level"));
    }

    public ScoreBuilder level(String label) {
      List<String> copy = new ArrayList<>(levels);
      copy.add(text(label, "label"));
      return new ScoreBuilder(instructions, copy);
    }

    public ScoreQuestion build() {
      if (levels.size() < 2
          || levels.size() > 10
          || levels.stream().distinct().count() != levels.size()) {
        throw new IllegalArgumentException("score requires 2-10 distinct levels");
      }
      return new ScoreQuestion(instructions, levels, 0);
    }
  }

  public record ScoreQuestion(String instructions, List<String> levels, double minConfidence) {
    public ScoreQuestion(String instructions, List<String> levels, double minConfidence) {
      this.instructions = text(instructions, "instructions");
      this.levels = List.copyOf(levels);
      this.levels.forEach(level -> text(level, "level"));
      if (this.levels.size() < 2
          || this.levels.size() > 10
          || this.levels.stream().distinct().count() != this.levels.size())
        throw new IllegalArgumentException("score requires 2-10 distinct levels");
      this.minConfidence = probability(minConfidence, "minConfidence");
    }

    public ScoreQuestion minConfidence(double value) {
      return new ScoreQuestion(instructions, levels, value);
    }

    /** Constructs local answer data; it does not contact or evaluate a remote service. */
    public ScoreAnswer answer(double value, List<Double> probabilities, double confidence) {
      return new ScoreAnswer(value, probabilities, confidence, minConfidence, levels.size());
    }
  }

  public static final class ScoreAnswer {
    private final double value;
    private final List<Double> probabilities;
    private final double confidence;
    private final boolean meetsThresholds;

    public ScoreAnswer(
        double value,
        List<Double> probabilities,
        double confidence,
        double minConfidence,
        int levelCount) {
      if (levelCount < 2 || levelCount > 10) {
        throw new IllegalArgumentException("levelCount out of range");
      }
      this.value = range(value, 0, levelCount - 1, "value");
      this.probabilities = validDistribution(probabilities, levelCount);
      this.confidence = probability(confidence, "confidence");
      this.meetsThresholds = confidence >= probability(minConfidence, "minConfidence");
    }

    public double value() {
      return value;
    }

    public List<Double> probabilities() {
      return probabilities;
    }

    public double confidence() {
      return confidence;
    }

    public boolean meetsThresholds() {
      return meetsThresholds;
    }

    public OptionalDouble acceptedValue() {
      return meetsThresholds ? OptionalDouble.of(value) : OptionalDouble.empty();
    }
  }

  private static String text(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }

  private static Map<Boolean, String> validDescriptions(Map<Boolean, String> descriptions) {
    Objects.requireNonNull(descriptions, "descriptions");
    Map<Boolean, String> copy = new LinkedHashMap<>();
    descriptions.forEach(
        (value, description) ->
            copy.put(
                Objects.requireNonNull(value, "description value"),
                text(description, "description")));
    return Map.copyOf(copy);
  }

  private static double probability(double value, String name) {
    return range(value, 0, 1, name);
  }

  private static double range(double value, double min, double max, String name) {
    if (!Double.isFinite(value) || value < min || value > max)
      throw new IllegalArgumentException(name + " out of range");
    return value;
  }

  private static <E extends Enum<E>> Class<E> enumType(Class<E> type, int min, int max) {
    Objects.requireNonNull(type, "enum type");
    E[] values = Objects.requireNonNull(type.getEnumConstants(), "type must be an enum");
    if (values.length < min || values.length > max)
      throw new IllegalArgumentException("enum size out of range");
    return type;
  }

  private static <E extends Enum<E>> E member(Class<E> type, E value) {
    Objects.requireNonNull(value, "enum value");
    if (value.getDeclaringClass() != type)
      throw new IllegalArgumentException("enum value has wrong type");
    return value;
  }

  private static <E extends Enum<E>> Map<E, Double> distribution(
      Class<E> type, Map<E, Double> input) {
    Objects.requireNonNull(input, "probabilities");
    Map<E, Double> result = new EnumMap<>(type);
    input.forEach(
        (key, value) ->
            result.put(
                member(type, key),
                probability(Objects.requireNonNull(value, "probability"), "probability")));
    if (result.size() != type.getEnumConstants().length)
      throw new IllegalArgumentException("probabilities must contain every enum value");
    requireUnitTotal(result.values());
    return Collections.unmodifiableMap(result);
  }

  private static List<Double> validDistribution(List<Double> input, int size) {
    Objects.requireNonNull(input, "probabilities");
    if (input.size() != size)
      throw new IllegalArgumentException("one probability per level required");
    List<Double> result =
        input.stream()
            .map(value -> probability(Objects.requireNonNull(value, "probability"), "probability"))
            .toList();
    requireUnitTotal(result);
    return result;
  }

  private static void requireUnitTotal(Iterable<Double> probabilities) {
    @Var double total = 0;
    for (double probability : probabilities) total += probability;
    if (Math.abs(total - 1.0) > DISTRIBUTION_SUM_TOLERANCE)
      throw new IllegalArgumentException("probabilities must sum to 1 within 0.000001");
  }
}
