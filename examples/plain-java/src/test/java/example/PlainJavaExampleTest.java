package example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.maxsumrall.jev4j.Jev;

import org.junit.jupiter.api.Test;

import java.util.Map;

class PlainJavaExampleTest {
    @Test
    void choiceRoutesAcceptedAndReviewAnswers() {
        Jev.ChoiceQuestion<PlainJavaExample.Destination> question =
                Jev.choice(PlainJavaExample.Destination.class, "Route it")
                        .minConfidence(0.70)
                        .minProbability(0.60);

        assertEquals(
                "ACCEPTED:BILLING",
                PlainJavaExample.route(
                        question.answer(
                                PlainJavaExample.Destination.BILLING,
                                Map.of(
                                        PlainJavaExample.Destination.BILLING,
                                        0.75,
                                        PlainJavaExample.Destination.SUPPORT,
                                        0.25),
                                0.80)));
        assertEquals(
                "REVIEW",
                PlainJavaExample.route(
                        question.answer(
                                PlainJavaExample.Destination.SUPPORT,
                                Map.of(
                                        PlainJavaExample.Destination.BILLING,
                                        0.45,
                                        PlainJavaExample.Destination.SUPPORT,
                                        0.55),
                                0.90)));
    }

    @Test
    void scoreMidpointRoundsUpAndLowConfidenceFallsBack() {
        Jev.EnumScoreQuestion<PlainJavaExample.Urgency> question =
                Jev.score(PlainJavaExample.Urgency.class, "Score urgency").minConfidence(0.65);
        Map<PlainJavaExample.Urgency, Double> probabilities =
                Map.of(
                        PlainJavaExample.Urgency.LOW,
                        0.10,
                        PlainJavaExample.Urgency.MEDIUM,
                        0.40,
                        PlainJavaExample.Urgency.HIGH,
                        0.50);

        assertEquals(
                "IMMEDIATE_QUEUE",
                PlainJavaExample.urgencyRoute(question.answer(1.5, probabilities, 0.65)));
        assertEquals(
                "REVIEW_LOW_CONFIDENCE",
                PlainJavaExample.urgencyRoute(question.answer(1.49, probabilities, 0.64)));
    }
}
