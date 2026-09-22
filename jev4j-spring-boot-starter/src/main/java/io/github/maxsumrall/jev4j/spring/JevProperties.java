package io.github.maxsumrall.jev4j.spring;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the auto-configured Jev evaluator.
 *
 * @param apiKey provider API key
 * @param provider provider preset to use
 * @param baseUri optional base URI overriding the provider preset
 * @param model model sent with evaluation requests
 * @param timeout finite, positive request timeout
 */
@ConfigurationProperties("jev")
public record JevProperties(
    @Nullable String apiKey,
    Provider provider,
    @Nullable URI baseUri,
    String model,
    Duration timeout) {
  public JevProperties {
    provider = provider == null ? Provider.TYPESAFE : provider;
    model = model == null ? "jev-latest" : model;
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
  }

  @Override
  public String toString() {
    return "JevProperties[apiKey=<redacted>, provider="
        + provider
        + ", baseUri="
        + baseUri
        + ", model="
        + model
        + ", timeout="
        + timeout
        + "]";
  }

  /** Supported provider presets. */
  public enum Provider {
    TYPESAFE,
    OPENROUTER
  }
}
