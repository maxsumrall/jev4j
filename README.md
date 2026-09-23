# jev4j

[![CI](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml)
[![OpenRouter component tests](https://github.com/maxsumrall/jev4j/actions/workflows/openrouter-component.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/openrouter-component.yml)

**Use AI decisions in ordinary Java code.**

Ask [Jev](https://docs.typesafe.ai/introduction), TypeSafe's structured decision model,
a question in plain English. Use the answer in Java.

**A question in an `if`.**

<!-- java: body -->
```java
if (jev.test("I want my money back!", Jev.noul("Is this a refund request?"))) {
  System.out.println("Start the refund workflow");
}
```

**Your enum in a `switch`.**

<!-- java: body -->
```java
enum Team { BILLING, DELIVERY, SUPPORT }

String inbox = switch (jev.evaluate(
    "My parcel never arrived.", Jev.choice(Team.class, "Which team can help?")).value()) {
  case BILLING -> "billing-support";
  case DELIVERY -> "delivery-support";
  case SUPPORT -> "general-support";
};
```

**A score on your scale.**

<!-- java: body -->
```java
enum Mood { CALM, FRUSTRATED, FURIOUS }

double frustrationScore = jev.evaluate(
    "This is the third failed delivery!",
    Jev.score(Mood.class, "How frustrated is the customer?")).value();
```

Get a fractional score from `0` (CALM) to `2` (FURIOUS).

These snippets use an evaluator named `jev`; [setup and imports are below](#get-started).
Each evaluation makes one provider request and may incur charges. Use
[acceptance thresholds](#thresholds-are-application-policy) before acting on uncertain results.

- Ask up to eight questions in one request and [map the answers into a record](#multi-question-results).
- Pass text or a [snapshot of your application data](#structured-state).
- Use blocking calls or `CompletableFuture`s with TypeSafe or OpenRouter.
- Add the Spring Boot 4 starter, or use core without Spring.
- Test your decisions offline with `question.answer(...)` fixtures.

**Java 17+ · MIT license · Available from Maven Central**

[Get started](#get-started) · [API guide](#api-guide) · [Spring Boot](#spring-boot) ·
[Runnable examples](#runnable-copyable-examples)

## Get started

**Early development:** the API may change. Replace `YOUR_VERSION` with your chosen
[Maven Central release](https://central.sonatype.com/artifact/io.github.maxsumrall.jev4j/jev4j-core):

```xml
<dependency>
  <groupId>io.github.maxsumrall.jev4j</groupId>
  <artifactId>jev4j-core</artifactId>
  <version>YOUR_VERSION</version>
</dependency>
```

Create an evaluator with your OpenRouter key and reuse it across requests:

<!-- java: members -->
```java
import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluator;

JevEvaluator jev = JevEvaluator.builder(System.getenv("OPENROUTER_API_KEY"))
    .openRouter()
    .model("jev-latest")
    .build();
```

For TypeSafe, use a TypeSafe key and `.typeSafe()` instead. Use a Jev model, not a chat model.
The examples below share this evaluator and earlier variables. Put declarations marked as enums,
records, or fields in your application class and call the remaining code from a method.

## API guide

### Noul: a probability of yes

Use `test(...)` for a boolean or `evaluate(...)` to keep the probability:

<!-- java: body -->
```java
import io.github.maxsumrall.jev4j.Jev.NoulAnswer;
import io.github.maxsumrall.jev4j.Jev.NoulQuestion;

NoulQuestion refundRequested = Jev.noul("Is the customer asking for money back?")
    .describe(true, "Requests a refund or reversal of a charge")
    .threshold(0.8);

NoulAnswer refund = jev.evaluate("Please refund the duplicate charge.", refundRequested);
boolean requested = refund.isTrue();         // probability >= 0.8
boolean clearRequest = refund.isTrueAt(0.95); // check a stricter cutoff without another call
```

A `false` result means the probability is below your yes threshold, not a confident no.
Keep a review range if you need both:

<!-- java: body -->
```java
double p = refund.probabilityTrue();
String decision = p >= 0.9 ? "YES" : p <= 0.1 ? "NO" : "REVIEW";
```

### Choice: route with an enum switch

Use your enum as the allowed options. Add descriptions on the question or implement
`Jev.Described` on the enum to keep them with the constants.

<!-- java: members -->
```java
import io.github.maxsumrall.jev4j.Jev.ChoiceAnswer;
import io.github.maxsumrall.jev4j.Jev.ChoiceQuestion;

enum Department { BILLING, DELIVERY, OTHER }
```

<!-- java: body -->
```java
ChoiceQuestion<Department> department =
    Jev.choice(Department.class, "Which team should handle this message?")
        .describe(Department.BILLING, "Charges, payments, and refunds")
        .describe(Department.DELIVERY, "Late, missing, or damaged deliveries")
        .minConfidence(0.85);

ChoiceAnswer<Department> classification = jev.evaluate("My groceries never arrived.", department);
String route = classification.acceptedValue()
    .map(Department::name)
    .orElse("MANUAL_REVIEW");
```

Use `acceptedValue()` or `meetsThresholds()` before routing. `value()` keeps the selected enum
even below your threshold; `probabilities()` and `confidence()` keep the provider's numbers.
`classification.is(Department.DELIVERY)` checks both the selection and its acceptance.
Descriptions do not remove options. Wire labels use `Enum.name()`, not `toString()`.

### Score: rate against ordered levels

Declare enum constants from lowest to highest, starting at zero. Reordering them changes the scale.
Use `.describe(level, text)` or implement `Jev.ScoreLevel` to describe each level.

| Answer method | Result |
| --- | --- |
| `value()` / `acceptedValue()` | Fractional score / optional score after the confidence check |
| `nearestLevel()` / `acceptedLevel()` | Nearest enum / optional enum after the confidence check; midpoints round up |
| `mostLikelyLevel()` | Enum with the highest probability; ties choose the first declared level |
| `probabilities()` / `confidence()` | Provider's distribution / confidence |

The nearest level can differ from the most likely level. Neither `nearestLevel()` nor
`mostLikelyLevel()` checks confidence; use `acceptedLevel()` before acting on the rounded score.

For a rubric without an enum:

<!-- java: body -->
```java
Jev.ScoreQuestion quality = Jev.score("How useful is this response?")
    .level("Unhelpful")
    .level("Partly useful")
    .level("Useful and complete")
    .build();
```

Score supports 2–10 levels; Choice supports up to 255 options. Both require a complete probability
distribution totaling 1 within `1e-6`. jev4j rejects invalid distributions rather than normalizing them.

### Thresholds are application policy

| Type | Configuration | Accept when |
| --- | --- | --- |
| Noul | `.threshold(0.8)` | Probability of yes ≥ 0.8; default is 0.5 |
| Choice | `.minConfidence(0.85)` | Jev confidence ≥ 0.85 |
| Choice | `.minProbability(0.9)` | Selected option's probability ≥ 0.9 |
| Score | `.minConfidence(0.85)` | Jev confidence ≥ 0.85 |

Choice and Score have no acceptance filter by default. Both Choice minimums must pass if you set
them. Confidence differs from probability; neither guarantees correctness.

Threshold checks run in your application without another request. Fluent methods return new
immutable questions, so keep their return values. A rejected answer keeps its original values.
Transport failures throw exceptions; they do not become `false`, `OTHER`, or an empty accepted result.

### Multi-question results

Ask two through eight questions about the same input in one request, then map the typed answers:

<!-- java: members -->
```java
record RoutingDecision(NoulAnswer refund, ChoiceAnswer<Department> department) {}
```

<!-- java: body -->
```java
RoutingDecision routing = jev.evaluate("Please refund this order.", refundRequested, department)
    .map(RoutingDecision::new);
```

The evaluation exposes `answer1()` through `answerN()` in question order and one set of request
metadata. Mapping makes no model call. Check each answer's acceptance before acting.

### Structured state

Use `Jev.State.from(...)` to take an immutable snapshot of a public record, string-keyed map,
list, or array:

<!-- java: members -->
```java
public record SupportTicket(String message, int failedPayments) {}
```

<!-- java: body -->
```java
Jev.State ticket = Jev.State.from(new SupportTicket("Please refund this order.", 2));
RoutingDecision ticketDecision = jev.evaluate(ticket, refundRequested, department)
    .map(RoutingDecision::new);
```

Reuse the snapshot across calls. Later changes to the source data cannot change it; avoid mutating
the source during snapshot creation. Strings stay literal text. Use `State.fromJson(...)` for JSON
or output from your own serializer; core exposes no Jackson types or mapper configuration.

State accepts JSON text, object, or array roots, with numbers, booleans, and null inside containers.
Null arguments throw `NullPointerException`. Invalid JSON, duplicate keys, cycles, unsupported
values, non-finite numbers, and resource-limit violations throw `IllegalArgumentException` before
HTTP dispatch. The nesting limit is 128 containers; other limits follow Jackson's defaults.

### Asynchronous evaluation

Use `evaluateAsync`, `evaluateWithMetadataAsync`, or `testAsync` for `CompletableFuture` results.
They accept text or State inputs. `evaluateAsync` also supports two through eight questions.

<!-- java: body -->
```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<JevEvaluator.Evaluation2<NoulAnswer, ChoiceAnswer<Department>>> operation =
    jev.evaluateAsync(ticket, refundRequested, department);
CompletableFuture<RoutingDecision> asyncRouting =
    operation.thenApply(result -> result.map(RoutingDecision::new));

// Cancel the original operation if you no longer need it.
operation.cancel(true);
```

Invalid arguments throw before dispatch; provider failures fail the future with `JevEvaluationException`.
`join()` wraps failures in `CompletionException`; `get()` uses `ExecutionException`.
Cancellation uses `CancellationException`.

Cancel the **original future**, not a dependent stage such as `asyncRouting`. Both `cancel(false)`
and `cancel(true)` request transport cancellation before caller completion callbacks run.
Cancellation is best effort: the provider may finish or charge for an in-flight request.
Do not complete or obtrude evaluation futures yourself.

`.timeout(...)` is an HTTP request timeout, not an end-to-end deadline. On Java 17 it does not
bound response-body reads after headers arrive. `get(timeout, unit)`, waiter interruption, and
`orTimeout` do not abort transport. For a caller deadline, time out a separate view such as
`operation.copy()` and cancel the original operation when the view times out.

Reuse the evaluator and client; jev4j neither creates an extra executor nor closes caller-owned resources.
Use `thenApplyAsync(..., yourExecutor)` for expensive callbacks.

### HTTP configuration and metadata

Configure `.timeout(Duration)`, `.baseUri(URI)`, or `.httpClient(HttpClient)` on the evaluator
builder. Both provider presets use `/v1/systemone`; OpenRouter's base URI is
`https://openrouter.ai/api`. Use trusted HTTPS endpoints: jev4j sends your bearer key and input
to the configured URI. Plain HTTP is for local tests.

<!-- java: body -->
```java
JevEvaluator.Evaluation<NoulAnswer> evaluation =
    jev.evaluateWithMetadata("Please refund this order.", refundRequested);
NoulAnswer result = evaluation.answer();
long inputTokens = evaluation.usage().inputTokens();
```

Evaluations also expose `model()`, `provider()`, optional `id()`, and optional `usage().cost()`.
`requestId()` on a result or exception reads the optional `x-typesafe-request-id` response header,
not the response-body `id()`. OpenRouter does not promise this header.

Catch `JevEvaluationException` and inspect `category()`: `HTTP`, `TIMEOUT`, `IO`, `INTERRUPTED`,
`MALFORMED_RESPONSE`, or `UNKNOWN`. `httpStatusCode()` is present for HTTP failures.
Synchronous interruption preserves the thread's interrupt flag. Evaluator-generated exceptions
omit provider bodies, API keys, and raw transport/parser causes; treat request IDs as provider data
before logging. jev4j has no automatic retries, streaming, or request splitting.

## Spring Boot

In a Spring Boot 4 application, replace `jev4j-core` with `jev4j-spring-boot-starter` at the same
version. Configure your provider and key, then inject `JevEvaluator` through your constructor:

```properties
jev.provider=openrouter
jev.api-key=${OPENROUTER_API_KEY}
```

See [starter configuration](jev4j-spring-boot-starter/README.md) for defaults and bean overrides.

## Runnable, copyable examples

Both standalone Maven examples run offline with synthetic answers by default:

| Example | Try |
| --- | --- |
| [Plain Java](examples/plain-java/README.md) | Thresholds, enum routing, and scores |
| [Spring Boot triage](examples/spring-boot-triage/README.md) | `POST /triage`, validation, review routing, and provider errors |

Use `question.answer(...)` to test your own decision logic without a model call:

<!-- java: body -->
```java
NoulAnswer synthetic = refundRequested.answer(0.84);
assert synthetic.isTrue();
assert !synthetic.isTrueAt(0.95);
```

## Testing and CI

Run the offline build from the repository root with JDK 17:

```shell
./mvnw clean install
./mvnw -f consumer-tests/pom.xml verify
./mvnw -f examples/plain-java/pom.xml verify exec:java
./mvnw -f examples/spring-boot-triage/pom.xml verify
```

[CI](.github/workflows/ci.yml) checks formatting, static analysis, public API contracts, and example
endpoints, including Java 21/25 compatibility. `ReadmeCompileTest` compiles the Java fences in this
README with Java 17 rules; it does not execute them. Keep the hidden `java: members` / `java: body`
markers on new Java fences so the test can assemble the shared scenario.

After changing the generated multi-question API, run:

```shell
python3 jev4j-core/generate-multi-evaluations.py
./mvnw -pl jev4j-core fmt:format
```

Commit the generated source. Repeating those commands on a clean checkout should leave no diff.

[OpenRouter component tests](.github/workflows/openrouter-component.yml) run on `main` pushes with
the repository's API key. They make up to three paid requests, one per primitive, without retries.
To opt in locally, set `OPENROUTER_API_KEY` and run:

```shell
./mvnw -pl jev4j-core -Popenrouter-component verify
```

Development uses `0.0.0-SNAPSHOT`; releases use CalVer `YYYY.M.N`, which indicates order rather than
API compatibility. See the [release guide](docs/releasing.md) for publishing.

## Upstream documentation

- [TypeSafe API](https://docs.typesafe.ai/api)
- [Noul thresholds](https://docs.typesafe.ai/primitives/noul) and [confidence versus probability](https://docs.typesafe.ai/confidence)
- [Jev through OpenRouter](https://openrouter.ai/docs/guides/community/typesafe-sdk)

## License

[MIT](LICENSE).
