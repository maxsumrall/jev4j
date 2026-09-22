package io.github.maxsumrall.jev4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maxsumrall.jev4j.JevEvaluator;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

final class JevAutoConfigurationTest {
  private static final String SECRET = "secret-that-must-not-leak";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JevAutoConfiguration.class));

  @Test
  void appliesDefaults() {
    contextRunner
        .withPropertyValues("jev.api-key=test-key")
        .run(
            context -> {
              assertThat(context).hasSingleBean(JevEvaluator.class);
              assertThat(field(context.getBean(JevEvaluator.class), "endpoint"))
                  .isEqualTo(URI.create("https://api.typesafe.ai/v1/systemone"));
              assertThat(field(context.getBean(JevEvaluator.class), "model"))
                  .isEqualTo("jev-latest");
              assertThat(field(context.getBean(JevEvaluator.class), "timeout"))
                  .isEqualTo(Duration.ofSeconds(30));
            });
  }

  @Test
  void bindsProviderAndOverrides() {
    contextRunner
        .withPropertyValues(
            "jev.api-key=test-key",
            "jev.provider=openrouter",
            "jev.base-uri=https://example.test/api",
            "jev.model=custom-model",
            "jev.timeout=2s")
        .run(
            context -> {
              JevEvaluator evaluator = context.getBean(JevEvaluator.class);
              assertThat(field(evaluator, "endpoint"))
                  .isEqualTo(URI.create("https://example.test/api/v1/systemone"));
              assertThat(field(evaluator, "model")).isEqualTo("custom-model");
              assertThat(field(evaluator, "timeout")).isEqualTo(Duration.ofSeconds(2));
            });
  }

  @Test
  void appliesOpenRouterPreset() {
    contextRunner
        .withPropertyValues("jev.api-key=test-key", "jev.provider=openrouter")
        .run(
            context ->
                assertThat(field(context.getBean(JevEvaluator.class), "endpoint"))
                    .isEqualTo(URI.create("https://openrouter.ai/api/v1/systemone")));
  }

  @Test
  void backsOffWithoutRequiringCredentials() {
    contextRunner
        .withUserConfiguration(CustomEvaluatorConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(JevEvaluator.class);
              assertThat(context.getBean(JevEvaluator.class))
                  .isSameAs(CustomEvaluatorConfiguration.EVALUATOR);
            });
  }

  @Test
  void missingKeyFailsClearlyWithoutLeakingOtherPropertyValues() {
    contextRunner
        .withPropertyValues("jev.model=" + SECRET)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .rootCause()
                  .hasMessage(
                      "jev.api-key must be configured when no JevEvaluator bean is supplied")
                  .hasMessageNotContaining(SECRET);
            });
  }

  @Test
  void propertiesToStringRedactsApiKey() {
    JevProperties properties =
        new JevProperties(
            SECRET, JevProperties.Provider.TYPESAFE, null, "jev-latest", Duration.ofSeconds(30));

    assertThat(properties.toString()).doesNotContain(SECRET);
  }

  @Test
  void usesUserHttpClient() {
    contextRunner
        .withPropertyValues("jev.api-key=test-key")
        .withUserConfiguration(HttpClientConfiguration.class)
        .run(
            context ->
                assertThat(field(context.getBean(JevEvaluator.class), "httpClient"))
                    .isSameAs(HttpClientConfiguration.CLIENT));
  }

  @Test
  void usesDefaultHttpClientWhenMultipleClientsExist() {
    contextRunner
        .withPropertyValues("jev.api-key=test-key")
        .withUserConfiguration(MultipleHttpClientsConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(JevEvaluator.class);
              assertThat(field(context.getBean(JevEvaluator.class), "httpClient"))
                  .isNotSameAs(MultipleHttpClientsConfiguration.FIRST)
                  .isNotSameAs(MultipleHttpClientsConfiguration.SECOND);
            });
  }

  private static Object field(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomEvaluatorConfiguration {
    private static final JevEvaluator EVALUATOR = JevEvaluator.builder("custom").build();

    @Bean
    JevEvaluator customEvaluator() {
      return EVALUATOR;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class HttpClientConfiguration {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Bean
    HttpClient httpClient() {
      return CLIENT;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class MultipleHttpClientsConfiguration {
    private static final HttpClient FIRST = HttpClient.newHttpClient();
    private static final HttpClient SECOND = HttpClient.newHttpClient();

    @Bean
    HttpClient firstHttpClient() {
      return FIRST;
    }

    @Bean
    HttpClient secondHttpClient() {
      return SECOND;
    }
  }
}
