# jev4j Spring Boot starter

Add `io.github.maxsumrall.jev4j:jev4j-spring-boot-starter` to your Spring Boot 4 application
([installation](../README.md#spring-boot)), set your key, and inject `JevEvaluator`:

```properties
jev.api-key=${TYPESAFE_API_KEY}
```

| Property | Default |
| --- | --- |
| `jev.provider` | `typesafe`; use `openrouter` with an OpenRouter key |
| `jev.model` | `jev-latest` |
| `jev.timeout` | `30s` HTTP request timeout |
| `jev.base-uri` | Provider preset: `https://api.typesafe.ai` or `https://openrouter.ai/api` |

Supply your own `JevEvaluator` bean to replace auto-configuration; that path needs no `jev.api-key`.
The starter uses a unique `java.net.http.HttpClient` bean if available, or creates a default client.
It does not close a caller-supplied client. Use trusted HTTPS URLs for base URI overrides.
