# jev4j

A Java client for [Jev](https://docs.typesafe.ai/introduction), TypeSafe's structured decision model.
Evaluate text as a yes/no probability, an enum choice, or a score against ordered levels.

[![CI](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml)
[![OpenRouter component tests](https://github.com/maxsumrall/jev4j/actions/workflows/openrouter-component.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/openrouter-component.yml)

```java
import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.Jev.NoulQuestion;

NoulQuestion isUrgent = Jev.noul("Is this urgent?");
if (jev.test(request, isUrgent)) {
  return "priority-support";
} else {
  return "normal-support";
}
```

`request` is the input text; `jev` is a configured `JevEvaluator`, created below.
`test(...)` makes one model request and
returns `true` when the probability of yes is at least `0.5`. Override the cutoff with
`.threshold(0.9)`.

- Immutable questions, answers, and evaluator builders.
- Typed enum results for exhaustive Java `switch` expressions.
- Local probability and confidence thresholds, with the original answers preserved.
- TypeSafe and OpenRouter through the JDK HTTP client; Jackson 3 stays internal.
- Optional Spring Boot 4 starter. No Spring dependency in core.

**Early development.** Requires Java 17; the API may change. Install the snapshot from source
until Maven Central releases are available.

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
import io.github.maxsumrall.jev4j.Jev.NoulAnswer;
import io.github.maxsumrall.jev4j.Jev.NoulQuestion;
import io.github.maxsumrall.jev4j.JevEvaluator;

JevEvaluator jev = JevEvaluator.builder(System.getenv("OPENROUTER_API_KEY"))
    .openRouter()
    .model("jev-latest")
    .build();

NoulQuestion refundRequested = Jev.noul("Is the customer asking for money back?")
    .describe(true, "Requests a refund or reversal of a charge")
    .describe(false, "Does not request money back")
    .threshold(0.8);

NoulAnswer answer =
    jev.evaluate("Please refund the duplicate charge.", refundRequested);
System.out.println(answer.isTrue());
System.out.println(answer.probabilityTrue());

if (jev.test("Please call me.", Jev.noul("Does this need a human response?"))) {
  System.out.println("queue for an agent");
}
```

The following snippets reuse these imports and evaluator. Each `evaluate(...)` call makes one
provider request and may incur charges. To call TypeSafe instead, use a TypeSafe key and
`.typeSafe()` (the default provider).

## Noul: a probability of yes

Jev calls its yes/no primitive **Noul**. A value near 1 means yes; near 0 means no.

```java
answer.isTrue();        // probability >= the question's threshold, 0.8
answer.isTrueAt(0.95);  // an override for this check only
```

Neither check makes another request or mutates the answer. Noul uses an inclusive `0.5` threshold
by default. `jev.test(...)` evaluates once and applies the configured threshold; it makes one
provider request and may incur charges.

A probability below the yes threshold may still be uncertain. Reserve a range for review:

```java
double p = answer.probabilityTrue();
String decision = p >= 0.9 ? "YES" : p <= 0.1 ? "NO" : "REVIEW";
```

## Choice: route with an enum switch

Define the allowed options with an enum. Implement `Jev.Described` to keep descriptions with
the constants:

```java
import io.github.maxsumrall.jev4j.Jev.ChoiceAnswer;
import io.github.maxsumrall.jev4j.Jev.ChoiceQuestion;

enum Department implements Jev.Described {
  BILLING("Charges, payments, and refunds"),
  DELIVERY("Late, missing, or damaged deliveries"),
  OTHER("Anything else");

  private final String description;

  Department(String description) {
    this.description = description;
  }

  @Override
  public String description() {
    return description;
  }
}

ChoiceQuestion<Department> department =
    Jev.choice(Department.class, "Which team should handle this message?")
    .minConfidence(0.85);

ChoiceAnswer<Department> classification =
    jev.evaluate("My groceries never arrived.", department);
```

Add this routing method to your application class:

```java
String queue(ChoiceAnswer<Department> answer) {
  if (!answer.meetsThresholds()) {
    return "manual-review";
  }
  return switch (answer.value()) {
      case BILLING -> "billing-support";
      case DELIVERY -> "delivery-support";
      case OTHER -> "general-support";
  };
}
```

`value()` retains the selected enum even when it fails acceptance checks. `probabilities()`
returns an immutable map keyed by the enum; `confidence()` retains Jev's reported statistic.
`classification.is(Department.DELIVERY)` tests whether Delivery is the accepted selection, including all
configured thresholds. An empty `acceptedValue()` means a local threshold failed, not a provider
error.

Choice and Score probability distributions must contain every declared option and total 1 within
an absolute tolerance of `1e-6`. This allows small floating-point or decimal-rounding differences;
jev4j rejects totals outside it rather than normalizing or recomputing scores or confidence.

For an existing enum, supply descriptions on the question instead:

```java
enum PlainDepartment { BILLING, DELIVERY, OTHER }

ChoiceQuestion<PlainDepartment> plainDepartment =
    Jev.choice(PlainDepartment.class, "Route it")
        .describe(PlainDepartment.BILLING, "Payments");
```

All constants remain allowed, including those without a description. `.describe(...)` changes
only the description. Wire labels use `Enum.name()`, not `toString()`.

## Score: rate against ordered levels

Implement `Jev.ScoreLevel` to describe each level. Declare constants from lowest to highest;
their positions define scores starting at zero. Reordering the enum changes the rubric.

```java
import io.github.maxsumrall.jev4j.Jev.EnumScoreAnswer;
import io.github.maxsumrall.jev4j.Jev.EnumScoreQuestion;

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

EnumScoreQuestion<Frustration> frustration =
    Jev.score(Frustration.class, "How frustrated is the customer?")
    .minConfidence(0.85);
EnumScoreAnswer<Frustration> rating =
    jev.evaluate("This is the third failed delivery!", frustration);

String priority = !rating.meetsThresholds() ? "review" : switch (rating.nearestLevel()) {
  case CALM -> "normal";
  case FRUSTRATED -> "priority";
  case VERY_ANGRY -> "specialist";
};
```

Jev returns a fractional score, such as 1.6. Read it through `value()` or `acceptedValue()`.
`acceptedLevel()` applies the confidence threshold and then rounds to the nearest enum; exact
midpoints round upward. `nearestLevel()` provides the same rounding without the acceptance guard.

Use `mostLikelyLevel()` for the enum with the highest reported probability. Ties choose the first
declared level. For example, with score `0.95` and probabilities `CALM: 0.45`, `FRUSTRATED: 0.15`,
`VERY_ANGRY: 0.40`:

```java
double score = rating.value();                       // 0.95
Frustration nearest = rating.nearestLevel();         // FRUSTRATED
Frustration mostLikely = rating.mostLikelyLevel();   // CALM
```

Neither helper applies the confidence threshold. Keep `probabilities()` and `confidence()` when
uncertainty matters; the most likely level need not have a majority of the probability.
`acceptedLevel()` still returns the nearest level, or an empty optional below the confidence threshold.

Use the optional level when composing with existing application methods:

```java
java.util.Optional<Frustration> accepted = rating.acceptedLevel();
```

For a rubric without an enum:

```java
import io.github.maxsumrall.jev4j.Jev.ScoreQuestion;

ScoreQuestion quality = Jev.score("How useful is this response?")
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

Comparisons include equality. Both Choice minimums must pass when configured. Choice and Score
have no acceptance filter by default. Confidence differs from selected-option probability;
neither guarantees correctness.

Thresholds never enter the provider request. Fluent methods return new immutable definitions;
keep their return values. Low confidence never becomes `OTHER`, and transport failures never
become `false` or an empty accepted result.

## HTTP configuration and metadata

Reuse an evaluator across requests. Configure its model, base URI, or timeout, or supply your
application's JDK `HttpClient` for proxy and TLS settings:

```java
import io.github.maxsumrall.jev4j.JevEvaluator.Evaluation;

JevEvaluator configured = JevEvaluator.builder(System.getenv("OPENROUTER_API_KEY"))
    .openRouter()
    .timeout(java.time.Duration.ofSeconds(15))
    .httpClient(java.net.http.HttpClient.newHttpClient())
    .build();

Evaluation<NoulAnswer> evaluation =
    configured.evaluateWithMetadata("Please refund this order.", refundRequested);
NoulAnswer result = evaluation.answer();
String servingModel = evaluation.model();
long tokens = evaluation.usage().inputTokens();
java.util.OptionalDouble optionalCost = evaluation.usage().cost();
java.util.Optional<String> requestId = evaluation.requestId();
```

Both presets call `/v1/systemone`; OpenRouter uses the base URI `https://openrouter.ai/api`.
Use a Jev model, not a chat-completions model. The evaluator does not retry requests or close a
caller-supplied HTTP client. HTTP, network, and malformed-response failures raise
`JevEvaluationException`; `httpStatusCode()` contains the numeric status only for HTTP failures.
Use `category()` to distinguish `FailureCategory.HTTP`, `TIMEOUT`, `IO` (including connection
failures), `INTERRUPTED`, and `MALFORMED_RESPONSE`. Interruption preserves the thread's interrupt
flag. The legacy message-only and message/cause exception constructors use `UNKNOWN`; the status
constructor uses `HTTP`. Existing constructors remain available.

Read `requestId()` on an evaluation or exception for the optional
[`x-typesafe-request-id` response header](https://docs.typesafe.ai/sdk/javascript/api/interfaces/WithResponse).
Missing or blank headers yield an empty optional, as do failures without a received response.
This differs from `id()`, the response-body ID that OpenRouter documents; jev4j does not infer
request IDs from body fields or undocumented headers. OpenRouter's System One docs do not promise
this header. Single and multi-question results expose the same request-level `requestId()`.

Evaluator-generated errors omit provider bodies and raw transport/parser causes, which may contain
sensitive data. Request IDs stay in their accessor, outside error messages; treat them as provider
data before logging. API keys must use bearer-token-safe characters and
are neither trimmed nor included in validation errors or their cause chains.

Evaluation is synchronous and accepts one question or two through eight typed questions with the
same String state. A multi-question evaluation uses one request and carries one set of metadata:

```java
record RoutingDecision(NoulAnswer refund, ChoiceAnswer<Department> department) {}

JevEvaluator.Evaluation2<NoulAnswer, ChoiceAnswer<Department>> evaluation =
    configured.evaluate("Please refund this order.", refundRequested, department);
RoutingDecision decision = evaluation.map(RoutingDecision::new);
```

The result exposes `answer1()` through `answerN()` in question order, plus request-level model,
usage, ID, and provider metadata. Mapping runs locally and preserves the answer types; use their
acceptance helpers before acting on uncertain results. Structured state, streaming, automatic retries, and more than
eight questions per request are not supported.

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

Build or copy the examples as standalone Maven projects. They stay outside the library artifacts
and dependency graph. Both run offline with labeled synthetic data by default.

| Example | Demonstrates |
| --- | --- |
| [Plain Java](examples/plain-java/README.md) | Noul thresholds, Choice switches, Score enum routing, optional live calls |
| [Spring Boot triage](examples/spring-boot-triage/README.md) | `POST /triage`, explicit Spring wiring, validation, review routing, sanitized provider errors |

After `./mvnw clean install`:

```shell
./mvnw -f consumer-tests/pom.xml verify
./mvnw -f examples/plain-java/pom.xml verify exec:java
./mvnw -f examples/spring-boot-triage/pom.xml verify
```

Each example's README includes run and copy instructions. Use `question.answer(...)` to construct
local test fixtures without a network call:

```java
NoulAnswer synthetic = refundRequested.answer(0.84);
assert synthetic.isTrue();
assert !synthetic.isTrueAt(0.95);
```

### Migrating pre-release Noul code

`ThresholdNoulQuestion` and `ThresholdNoulAnswer` were removed. Use `NoulQuestion` and
`NoulAnswer`; both now carry a threshold, defaulting to `0.5`.
The pre-release evaluator argument order also changed: use `evaluate(state, question)` and
`evaluateWithMetadata(state, question)`.

## Testing and CI

After changing the generated arity-specific API, run
`python3 jev4j-core/generate-multi-evaluations.py && ./mvnw -pl jev4j-core fmt:format`; generated
source is checked in. Run those commands on a clean checkout followed by `git diff --exit-code` as
the regeneration check.

[Offline CI](.github/workflows/ci.yml) runs on pushes and pull requests. The standalone
`consumer-tests` project exercises installed artifacts through public APIs and a real local HTTP
server, using structural golden JSON fixtures and compile-time generic contracts. That suite also
checks credential redaction and transport failures. Focused core tests retain numeric boundaries,
defensive-copy behavior, and construction invariants.
The Spring example tests real random-port HTTP endpoints in offline and local-provider modes.
Small Spring context tests cover bean replacement and missing credentials without inspecting
private fields. Compatibility jobs compile the consumer and Spring application suites on Java 17,
then run their bytecode on Java 21 and 25. Error Prone/Picnic, NullAway, and formatting checks
remain part of the build.

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

Publishing remains separate from CI. The manual [Maven Central release workflow](.github/workflows/release.yml)
tests a release tag, signs the library artifacts, and publishes through Sonatype Central.
See the [release guide](docs/releasing.md) for account setup, required secrets, and release steps.
Example projects disable deployment.

## Upstream documentation

- [TypeSafe API reference](https://docs.typesafe.ai/api)
- [Noul and probability thresholds](https://docs.typesafe.ai/primitives/noul)
- [Confidence versus probability](https://docs.typesafe.ai/confidence)
- [Jev through OpenRouter](https://openrouter.ai/docs/guides/community/typesafe-sdk)
- [Picnic Error Prone Support](https://github.com/PicnicSupermarket/error-prone-support), our build-tooling reference
