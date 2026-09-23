package io.github.maxsumrall.jev4j.spring;

import com.google.errorprone.annotations.Var;
import io.github.maxsumrall.jev4j.JevEvaluator;
import java.net.http.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configures a {@link JevEvaluator} from {@code jev.*} properties. */
@AutoConfiguration
@EnableConfigurationProperties(JevProperties.class)
public class JevAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  JevEvaluator jevEvaluator(
      JevProperties properties, ObjectProvider<HttpClient> httpClientProvider) {
    String apiKey = properties.apiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "jev.api-key must be configured when no JevEvaluator bean is supplied");
    }

    @Var JevEvaluator.Builder builder = JevEvaluator.builder(apiKey);
    builder =
        properties.provider() == JevProperties.Provider.OPENROUTER
            ? builder.openRouter()
            : builder.typeSafe();
    if (properties.baseUri() != null) builder = builder.baseUri(properties.baseUri());
    builder = builder.model(properties.model()).timeout(properties.timeout());
    HttpClient httpClient = httpClientProvider.getIfUnique();
    if (httpClient != null) builder = builder.httpClient(httpClient);
    return builder.build();
  }
}
