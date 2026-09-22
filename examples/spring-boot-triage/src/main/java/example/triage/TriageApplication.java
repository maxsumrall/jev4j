package example.triage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({OfflineAnswerConfiguration.class, LiveAnswerConfiguration.class, TriageController.class})
public class TriageApplication {
  @Bean
  TriageService triageService(TriageAnswerSource answerSource) {
    return new TriageService(answerSource);
  }

  public static void main(String[] args) {
    SpringApplication.run(TriageApplication.class, args);
  }
}
