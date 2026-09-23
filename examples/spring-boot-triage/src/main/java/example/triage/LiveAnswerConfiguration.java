package example.triage;

import io.github.maxsumrall.jev4j.JevEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("live")
class LiveAnswerConfiguration {
  @Bean
  TriageAnswerSource liveAnswerSource(JevEvaluator evaluator) {
    return message ->
        evaluator
            .evaluate(
                message, TriageQuestions.CATEGORY, TriageQuestions.SAFETY, TriageQuestions.URGENCY)
            .map(
                (category, safety, urgency) ->
                    new TriageAnswers(category, safety, urgency, "live-openrouter"));
  }
}
