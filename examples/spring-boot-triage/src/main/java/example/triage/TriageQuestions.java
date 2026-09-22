package example.triage;

import io.github.maxsumrall.jev4j.Jev;

final class TriageQuestions {
  static final Jev.ChoiceQuestion<Category> CATEGORY =
      Jev.choice(Category.class, "Choose the supermarket support category")
          .minConfidence(0.70)
          .minProbability(0.60);
  static final Jev.NoulQuestion SAFETY =
      Jev.noul("Does this message describe an immediate health or safety concern?");
  static final Jev.EnumScoreQuestion<Urgency> URGENCY =
      Jev.score(Urgency.class, "Score how urgently a human should review this request");

  private TriageQuestions() {}
}
