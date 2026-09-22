# jev4j Spring Boot starter

Add `io.github.maxsumrall.jev4j:jev4j-spring-boot-starter` to a Spring Boot 4
application, then configure the evaluator:

```properties
jev.api-key=${TYPESAFE_API_KEY}
jev.model=jev-latest
jev.timeout=30s
```

The defaults use the TypeSafe provider and `https://api.typesafe.ai`. For OpenRouter set
`jev.provider=openrouter`. `jev.base-uri` overrides either provider preset. The starter creates a
`JevEvaluator` only when the application has not supplied one, so a custom evaluator needs no
`jev.api-key`. If the context has a single `java.net.http.HttpClient` bean, the auto-configured
evaluator uses it; otherwise the core evaluator's default client is used. The evaluator does not
close an injected client.
