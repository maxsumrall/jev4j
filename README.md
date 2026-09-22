# jev4j

**Typed Jev decisions for Java.** Ask a yes/no question, select an enum, or score an ordered
rubric. Keep the probabilities and decide in Java when to act.

[![CI](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml)
[![OpenRouter component tests](https://github.com/maxsumrall/jev4j/actions/workflows/openrouter-component.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/openrouter-component.yml)

- Immutable questions, answers, and evaluator builders.
- Typed enum results for exhaustive Java `switch` expressions.
- Local probability and confidence thresholds, with the original answers preserved.
- TypeSafe and OpenRouter through the JDK HTTP client; Jackson 3 stays internal.
- Optional Spring Boot 4 starter. No Spring dependency in core.

**Status:** early development, Java 17 baseline. The API may change. Artifacts are not yet
published to Maven Central; install from source to use the snapshot.

## Get started

Use JDK 17 to build the repository. The Maven Wrapper downloads Maven for you:

```shell
./mvnw clean install
```

Then add the core library to your application:

```xml
<dependency>
  <groupId>io.github.maxsumrall.jev4j</groupId>
  <artifactId>jev4j-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Create an evaluator using your OpenRouter key from the environment:

```java
import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluator;

var evaluator = JevEvaluator.builder(System.getenv("OPENROUTER_API_KEY"))
    .openRouter()
    .model("jev-latest")
    .build();

var refundRequested = Jev.noul("Is the customer asking for money back?")
    .describe(true, "Requests a refund or reversal of a charge")
    .describe(false, "Does not request money back")
    .threshold(0.8);

var answer = evaluator.evaluate(refundRequested, "Please refund the duplicate charge.");
System.out.println(answer.isTrue());
System.out.println(answer.probabilityTrue());
```

The Java snippets below use these imports and evaluator. Each `evaluate(...)` call makes one
provider request and may incur charges. Use `.typeSafe()` with a TypeSafe key for the direct
provider; TypeSafe is the default when neither preset is supplied.

## Noul: a probability of yes

Jev calls its yes/no primitive **Noul**. A value near 1 means yes; near 0 means no.

```java
answer.isTrue();        // probability >= the question's threshold, 0.8
answer.isTrueAt(0.95);  // an override for this check only
```

Neither check makes another request or mutates the answer. A question without `.threshold(...)`
returns a probability-only answer with `isTrueAt(...)`, but no `isTrue()` method.

**Below the yes threshold does not mean confidently no.** Use a review region when needed:

```java
double p = answer.probabilityTrue();
String decision = p >= 0.9 ? "YES" : p <= 0.1 ? "NO" : "REVIEW";
```

## Choice: route with an enum switch

Every enum constant is an allowed option. `.describe(...)` explains an option; it does not add
or remove options.

```java
enum Department { BILLING, DELIVERY, OTHER }

var department = Jev.choice(Department.class, "Which team should handle this message?")
    .describe(Department.BILLING, "Charges, payments, and refunds")
    .describe(Department.DELIVERY, "Late, missing, or damaged deliveries")
    .describe(Department.OTHER, "Anything else")
    .minConfidence(0.85);

var classification = evaluator.evaluate(department, "My groceries never arrived.");

String queue = classification.acceptedValue()
    .map(value -> switch (value) {
      case BILLING -> "billing-support";
      case DELIVERY -> "delivery-support";
      case OTHER -> "general-support";
    })
    .orElse("manual-review");
```

`value()` retains the selected enum even when it fails acceptance checks. `probabilities()`
returns an immutable map keyed by the enum; `confidence()` retains Jev's reported statistic.
An empty `acceptedValue()` means a local threshold failed, not a provider error.

You can put reusable descriptions on enums by implementing `Jev.Described.description()`.
Plain enums need no library interface. Wire labels use `Enum.name()`, not `toString()`.

## Score: ordered levels with an explicit enum conversion

Implement the optional `Jev.ScoreLevel` interface to keep descriptions with your enum. Declaration
order defines levels from lowest to highest, starting at zero; reordering changes the rubric.

```java
enum Frustration implements Jev.ScoreLevel {
  CALM("No frustration expressed"),
  FRUSTRATED("Dissatisfaction or impatience"),
  VERY_ANGRY("Strong anger");

  private final String description;

  Frustration(String description) {
    this.description = description;
  }

  @Override
  public String description() {
    return description;
  }
}

var frustration = Jev.score(Frustration.class, "How frustrated is the customer?")
    .minConfidence(0.85);
var rating = evaluator.evaluate(frustration, "This is the third failed delivery!");

String priority = !rating.meetsThresholds() ? "review" : switch (rating.nearestLevel()) {
  case CALM -> "normal";
  case FRUSTRATED -> "priority";
  case VERY_ANGRY -> "specialist";
};
```

**A Score can be fractional.** `value()` and `acceptedValue()` preserve the raw score, such as
1.6. `nearestLevel()` rounds to the closest enum level, with exact midpoints rounding upward.
That is a local conversion, not a category selected by Jev.

For a rubric without an enum:

```java
var quality = Jev.score("How useful is this response?")
    .level("Unhelpful")
    .level("Partly useful")
    .level("Useful and complete")
    .build();
```

Score rubrics require 2–10 levels. Choice supports up to 255 enum constants.

## Thresholds are application policy

| Type | Configuration | Meaning |
| --- | --- | --- |
| Noul | `.threshold(0.8)` | Interpret probability of yes ≥ 0.8 as true |
| Choice | `.minConfidence(0.85)` | Accept when Jev confidence ≥ 0.85 |
| Choice | `.minProbability(0.9)` | Accept when the selected option's probability ≥ 0.9 |
| Score | `.minConfidence(0.85)` | Accept the rating when Jev confidence ≥ 0.85 |

All comparisons are inclusive. If both Choice minimums are configured, both must pass. Choice
and Score have no acceptance filter by default. Confidence and selected-option probability are
different quantities; neither should be interpreted as a guarantee of correctness.

Thresholds never enter the provider request. Fluent methods return new immutable definitions;
keep their return values. Low confidence never becomes `OTHER`, and transport failures never
become `false` or an empty accepted result.

## HTTP configuration and metadata

The evaluator and builder are immutable and reusable. Configure a model, request timeout, base
URI, or your application's JDK `HttpClient` for proxy/TLS settings:

```java
var configured = JevEvaluator.builder(System.getenv("OPENROUTER_API_KEY"))
    .openRouter()
    .timeout(java.time.Duration.ofSeconds(15))
    .httpClient(java.net.http.HttpClient.newHttpClient())
    .build();

var evaluation = configured.evaluateWithMetadata(refundRequested, "Please refund this order.");
var result = evaluation.answer();
var servingModel = evaluation.model();
var tokens = evaluation.usage().inputTokens();
var optionalCost = evaluation.usage().cost();
```

Both presets call `/v1/systemone`; OpenRouter uses the base URI `https://openrouter.ai/api`.
Use a Jev model, not a chat-completions model. The evaluator does not retry requests or close a
caller-supplied HTTP client. HTTP, network, and malformed-response failures raise
`JevEvaluationException`; error messages omit provider response bodies.

Current scope is synchronous evaluation of **one question with String state per request**.
Structured state, batching, streaming, and automatic retries are not implemented.

## Spring Boot

Use `jev4j-spring-boot-starter` instead of the core dependency in a Spring Boot 4 application:

```xml
<dependency>
  <groupId>io.github.maxsumrall.jev4j</groupId>
  <artifactId>jev4j-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```properties
jev.provider=openrouter
jev.api-key=${OPENROUTER_API_KEY}
jev.model=jev-latest
jev.timeout=30s
```

Inject `JevEvaluator` through your constructor. Supply your own evaluator bean to replace the
auto-configured one; that path requires no `jev.api-key`. See the
[starter documentation](jev4j-spring-boot-starter/README.md) for configuration details.

## Runnable, copyable examples

Examples are standalone Maven projects, outside the library reactor. They do not ship in library
JARs or become transitive dependencies. Both default to clearly labeled synthetic offline data.

| Example | Demonstrates |
| --- | --- |
| [Plain Java](examples/plain-java/README.md) | Noul thresholds, Choice switches, Score enum routing, optional live calls |
| [Spring Boot triage](examples/spring-boot-triage/README.md) | `POST /triage`, explicit Spring wiring, validation, review routing, sanitized provider errors |

After `./mvnw clean install`:

```shell
./mvnw -f examples/plain-java/pom.xml verify exec:java
./mvnw -f examples/spring-boot-triage/pom.xml verify
```

Follow each example's README to run or copy it outside this repository. For local fixtures in your
own tests, `question.answer(...)` constructs immutable answer data without a network call:

```java
var synthetic = refundRequested.answer(0.84);
assert synthetic.isTrue();
assert !synthetic.isTrueAt(0.95);
```

## Testing and CI

[Offline CI](.github/workflows/ci.yml) runs on pushes and pull requests. It builds on Java 17
with Error Prone, Picnic checks, NullAway, formatting checks, and unit/component tests. It builds
both examples independently. Compatibility jobs compile on Java 17, then run core and starter
tests on Java 21 and 25 without recompiling against newer javac internals.

[OpenRouter component tests](.github/workflows/openrouter-component.yml) run on pushes to `main`
and support manual runs on `main`. They use the repository's `OPENROUTER_API_KEY` Actions secret
and make up to three real requests, one per primitive, with synthetic inputs, 30-second request
timeouts, and no retries. A successful run completes all three. The checks validate response
contracts rather than exact model probabilities. Pull-request jobs receive no provider secret.

To opt in locally, set `OPENROUTER_API_KEY` in your environment, then run:

```shell
./mvnw -pl jev4j-core -Popenrouter-component verify
```

This incurs provider charges. Missing credentials fail the explicit live run. The Spring example's
`LiveProfileIntegrationTest` uses a **local mock provider**, not OpenRouter.

Maven Central releases are a later step. No publishing workflow exists, and example projects
disable deployment.

## Upstream documentation

- [TypeSafe API reference](https://docs.typesafe.ai/api)
- [Noul and probability thresholds](https://docs.typesafe.ai/primitives/noul)
- [Confidence versus probability](https://docs.typesafe.ai/confidence)
- [Jev through OpenRouter](https://openrouter.ai/docs/guides/community/typesafe-sdk)
- [Picnic Error Prone Support](https://github.com/PicnicSupermarket/error-prone-support), our build-tooling reference
