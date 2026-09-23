package io.github.maxsumrall.jev4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.maxsumrall.jev4j.JevEvaluator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

final class JevAutoConfigurationContractTest {
    private static final String SECRET = "secret-that-must-not-leak";
    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JevAutoConfiguration.class));

    @Test
    void userEvaluatorBacksOffWithoutCredentials() {
        contexts.withUserConfiguration(CustomEvaluatorConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed().hasSingleBean(JevEvaluator.class);
                            assertThat(context.getBean(JevEvaluator.class))
                                    .isSameAs(CustomEvaluatorConfiguration.EVALUATOR);
                        });
    }

    @Test
    void missingKeyFailsClearlyWithoutLeakingOtherProperties() {
        contexts.withPropertyValues("jev.model=" + SECRET)
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
    void propertiesNeverRenderApiKey() {
        JevProperties properties =
                new JevProperties(
                        SECRET,
                        JevProperties.Provider.TYPESAFE,
                        null,
                        "jev-latest",
                        Duration.ofSeconds(30));
        assertThat(properties.toString()).doesNotContain(SECRET);
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomEvaluatorConfiguration {
        private static final JevEvaluator EVALUATOR = JevEvaluator.builder("custom").build();

        @Bean
        JevEvaluator customEvaluator() {
            return EVALUATOR;
        }
    }
}
