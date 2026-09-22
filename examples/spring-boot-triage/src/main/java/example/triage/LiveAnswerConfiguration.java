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
        new TriageAnswers(
            evaluator.evaluate(TriageQuestions.CATEGORY, message),
            evaluator.evaluate(TriageQuestions.SAFETY, message),
            evaluator.evaluate(TriageQuestions.URGENCY, message),
            "live-openrouter");
  }
}
