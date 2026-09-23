# jev4j

[![CI](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml/badge.svg)](https://github.com/maxsumrall/jev4j/actions/workflows/ci.yml)

jev4j lets you ask [Jev](https://docs.typesafe.ai/introduction), TypeSafe's structured decision
model, a question in plain English and use the answer as ordinary Java: a boolean in an `if`, one
of your own enum constants in a `switch`, or a point on a scale you define.

<!-- java: body -->
```java
var isRefundRequest = Jev.noul("Is this a refund request?");

if (jev.test("I want my money back!", isRefundRequest)) {
    System.out.println("Start the refund workflow");
}
```

Give it an enum and it picks a constant, so the compiler checks that you handled every case:

<!-- java: body -->
```java
enum Team {
    BILLING,
    DELIVERY,
    SUPPORT
}

String inbox =
        switch (jev.evaluate(
                        "My parcel never arrived.",
                        Jev.choice(Team.class, "Which team can help?"))
                .value()) {
            case BILLING -> "billing-support";
            case DELIVERY -> "delivery-support";
            case SUPPORT -> "general-support";
        };
```

Or ask it to place the input on an ordered scale:

<!-- java: body -->
```java
enum Mood {
    CALM,
    FRUSTRATED,
    FURIOUS
}

Mood mood =
        jev.evaluate(
                        "This is the third failed delivery!",
                        Jev.score(Mood.class, "How frustrated is the customer?"))
                .nearestLevel();
```

`nearestLevel()` rounds to a `Mood`. If you want the fractional score, call `.value()` instead.

These snippets use an evaluator called `jev`, which [Get started](#get-started) shows you how to
build. Each evaluation sends one request to your provider, and providers can charge for it. Every
answer comes with its probabilities, so you can [set a threshold](#you-decide-how-sure-is-sure-enough)
and only act when the model is sure enough.

You can also ask up to eight questions in one request and
[map the answers into a record](#several-questions-in-one-request), or pass
[a snapshot of your own data](#structured-input) in place of text. Calls can block or return a
`CompletableFuture`, and jev4j talks to either TypeSafe or OpenRouter. It works in plain Java and
has a [Spring Boot 4 starter](#spring-boot). For tests, you can build answers yourself and never
touch the network.

jev4j needs Java 17 or newer and uses the MIT license.

## Get started

jev4j is young, and the API may still change. Add the core library from
[Maven Central](https://central.sonatype.com/artifact/io.github.maxsumrall.jev4j/jev4j-core),
putting the latest release in place of `YOUR_VERSION`:

```xml
<dependency>
  <groupId>io.github.maxsumrall.jev4j</groupId>
  <artifactId>jev4j-core</artifactId>
  <version>YOUR_VERSION</version>
</dependency>
```

Build one evaluator with your OpenRouter key and reuse it for every request:

<!-- java: members -->
```java
import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluator;

JevEvaluator jev =
        JevEvaluator.builder(System.getenv("OPENROUTER_API_KEY"))
                .openRouter()
                .model("jev-latest")
                .build();
```

To call TypeSafe directly, pass a TypeSafe key and use `.typeSafe()` in place of `.openRouter()`.
Either way, choose a Jev model such as `jev-latest` rather than a chat model.

The examples below build on each other and share this evaluator. Put the fields, enums, and
records in your class, and run the rest from a method.

## Three kinds of question

Jev answers three kinds of question. A **Noul** is a yes-or-no question, answered with the
probability of yes. A **Choice** picks one constant from your enum. A **Score** rates the input on
an ordered scale that you define.

### Noul: yes or no, with a probability

`test(...)` gives you a boolean. `evaluate(...)` gives you the whole answer, probability included,
so you can check a second cutoff without paying for a second call:

<!-- java: body -->
```java
import io.github.maxsumrall.jev4j.Jev.NoulAnswer;
import io.github.maxsumrall.jev4j.Jev.NoulQuestion;

NoulQuestion refundRequested =
        Jev.noul("Is the customer asking for money back?")
                .describe(true, "Requests a refund or reversal of a charge")
                .threshold(0.8);

NoulAnswer refund = jev.evaluate("Please refund the duplicate charge.", refundRequested);
boolean requested = refund.isTrue(); // probability >= 0.8
boolean clearRequest =
        refund.isTrueAt(0.95); // check a stricter cutoff without another call
```

A `false` from `isTrue()` only tells you the probability fell below your threshold. The model may
still be unsure. When a confident no matters to you, leave a band in the middle for a person to
review:

<!-- java: body -->
```java
double p = refund.probabilityTrue();
String decision = p >= 0.9 ? "YES" : p <= 0.1 ? "NO" : "REVIEW";
```

### Choice: pick one of your enum constants

Your enum defines the options. You can describe each constant on the question, as below, or
implement `Jev.Described` on the enum so each description sits next to its constant.

<!-- java: members -->
```java
import io.github.maxsumrall.jev4j.Jev.ChoiceAnswer;
import io.github.maxsumrall.jev4j.Jev.ChoiceQuestion;

enum Department {
    BILLING,
    DELIVERY,
    OTHER
}
```

<!-- java: body -->
```java
ChoiceQuestion<Department> department =
        Jev.choice(Department.class, "Which team should handle this message?")
                .describe(Department.BILLING, "Charges, payments, and refunds")
                .describe(Department.DELIVERY, "Late, missing, or damaged deliveries")
                .minConfidence(0.85);

ChoiceAnswer<Department> classification =
        jev.evaluate("My groceries never arrived.", department);
String route = classification.acceptedValue().map(Department::name).orElse("MANUAL_REVIEW");
```

Check `acceptedValue()` or `meetsThresholds()` before you route anything. `value()` returns the
model's pick even when it falls short of your threshold, and `probabilities()` and `confidence()`
return the provider's raw numbers. `classification.is(Department.DELIVERY)` checks the pick and its
acceptance in one call.

Descriptions guide the model, but every constant stays a possible answer. On the wire, jev4j names
each option with `Enum.name()` and ignores any `toString()` override.

### Score: rate on an ordered scale

Declare your enum constants from lowest to highest. jev4j treats the first constant as zero and
counts up from there, so reordering the constants changes the scale. Describe the levels with
`.describe(level, text)` or by implementing `Jev.ScoreLevel`.

A score answer offers a few views of the same result:

| Answer method | Result |
| --- | --- |
| `value()` / `acceptedValue()` | Fractional score / optional score after the confidence check |
| `nearestLevel()` / `acceptedLevel()` | Nearest enum / optional enum after the confidence check; midpoints round up |
| `mostLikelyLevel()` | Enum with the highest probability; ties choose the first declared level |
| `probabilities()` / `confidence()` | Provider's distribution / confidence |

The score and the distribution can point at different levels, so `nearestLevel()` and
`mostLikelyLevel()` may disagree. Neither one checks confidence. Use `acceptedLevel()` when you
plan to act on the rounded score.

If an enum feels like too much, build the rubric inline:

<!-- java: body -->
```java
Jev.ScoreQuestion quality =
        Jev.score("How useful is this response?")
                .level("Unhelpful")
                .level("Partly useful")
                .level("Useful and complete")
                .build();
```

A Score takes 2 to 10 levels, and a Choice takes up to 255 options. The provider has to return a
full probability distribution that sums to 1 within `1e-6`. jev4j rejects any answer that misses,
and it won't normalize the numbers for you.

### You decide how sure is sure enough

jev4j leaves the acceptance rules to you. Set them per question:

| Type | Configuration | Accept when |
| --- | --- | --- |
| Noul | `.threshold(0.8)` | Probability of yes ≥ 0.8; default is 0.5 |
| Choice | `.minConfidence(0.85)` | Jev confidence ≥ 0.85 |
| Choice | `.minProbability(0.9)` | Selected option's probability ≥ 0.9 |
| Score | `.minConfidence(0.85)` | Jev confidence ≥ 0.85 |

Choice and Score accept any answer until you set a minimum. If you set both Choice minimums, an
answer has to clear both. Confidence and probability measure different things, and a high number
on either one can still come with a wrong answer. TypeSafe covers the difference in
[confidence versus probability](https://docs.typesafe.ai/confidence).

jev4j checks thresholds in your process, so trying a new cutoff costs nothing. The fluent methods
return a new immutable question each time, so keep the return value. A rejected answer still holds
its original values. When the network or provider fails, you get an exception. jev4j never turns a
failure into `false`, `OTHER`, or an empty result.

### Several questions in one request

When you have two to eight questions about the same input, send them together and map the typed
answers straight into a record:

<!-- java: members -->
```java
record RoutingDecision(NoulAnswer refund, ChoiceAnswer<Department> department) {}
```

<!-- java: body -->
```java
RoutingDecision routing =
        jev.evaluate("Please refund this order.", refundRequested, department)
                .map(RoutingDecision::new);
```

You can also read the answers one at a time with `answer1()` through `answerN()`, in the order you
asked. The questions share one set of request metadata, and `map` runs locally. Check each
answer's acceptance on its own before you act on it.

### Structured input

Jev can read your data as well as text. `Jev.State.from(...)` takes an immutable snapshot of a
public record, a map with string keys, a list, or an array:

<!-- java: members -->
```java
public record SupportTicket(String message, int failedPayments) {}
```

<!-- java: body -->
```java
Jev.State ticket = Jev.State.from(new SupportTicket("Please refund this order.", 2));
RoutingDecision ticketDecision =
        jev.evaluate(ticket, refundRequested, department).map(RoutingDecision::new);
```

Reuse the snapshot as often as you like. Changes you make to the source object afterwards won't
reach it, but don't mutate the source while `from(...)` runs. A `String` goes in as literal text,
and jev4j won't parse it. If you already have JSON, or want your own serializer, pass the JSON to
`State.fromJson(...)`. Core keeps Jackson out of its public API, so you never configure a mapper.

The root of a State must be a string, an object, or an array. Numbers, booleans, and null can
appear inside containers. jev4j checks your input before it sends anything. A null argument
throws `NullPointerException`. Invalid JSON, duplicate keys, cycles, unsupported values,
non-finite numbers, and input past the size limits throw `IllegalArgumentException`. Nesting stops
at 128 containers, and the other limits match Jackson's defaults.

### Asynchronous calls

`evaluateAsync`, `evaluateWithMetadataAsync`, and `testAsync` return a `CompletableFuture`. They
take text or a State, and `evaluateAsync` also accepts two to eight questions:

<!-- java: body -->
```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<JevEvaluator.Evaluation2<NoulAnswer, ChoiceAnswer<Department>>>
        operation = jev.evaluateAsync(ticket, refundRequested, department);
CompletableFuture<RoutingDecision> asyncRouting =
        operation.thenApply(result -> result.map(RoutingDecision::new));

// Cancel the original operation if you no longer need it.
operation.cancel(true);
```

Bad arguments still throw right away. Provider failures complete the future with a
`JevEvaluationException`, which `join()` wraps in `CompletionException` and `get()` wraps in
`ExecutionException`. A cancelled future throws `CancellationException`.

To cancel, call `cancel` on the **original future** (`operation` above), because cancelling a
dependent stage like `asyncRouting` won't reach the request. `cancel(false)` and `cancel(true)` both
ask the transport to stop before your completion callbacks run. The provider may have started the
work already and can still bill you for it. Let jev4j complete its own futures, and don't call
`complete` or `obtrude` on them.

`.timeout(...)` limits the HTTP request, and on Java 17 it stops counting once the headers arrive,
so a slow response body can run past it. `get(timeout, unit)`, interrupting the waiting thread, and
`orTimeout` leave the request running too. For a real deadline, put the timeout on a copy such as
`operation.copy()`, and cancel the original when the copy times out.

jev4j starts no executor of its own and closes nothing you hand it. Reuse the evaluator and your
`HttpClient`, and run expensive callbacks on your own executor with
`thenApplyAsync(..., yourExecutor)`.

### HTTP, metadata, and errors

The evaluator builder takes `.timeout(Duration)`, `.baseUri(URI)`, and `.httpClient(HttpClient)`.
Both provider presets call `/v1/systemone`, and the OpenRouter preset uses
`https://openrouter.ai/api` as its base URI. jev4j sends your API key and your input to whatever
URI you configure, so point it at HTTPS endpoints you trust. Save plain HTTP for local tests.

Call `evaluateWithMetadata` when you want to see what a request used:

<!-- java: body -->
```java
JevEvaluator.Evaluation<NoulAnswer> evaluation =
        jev.evaluateWithMetadata("Please refund this order.", refundRequested);
NoulAnswer result = evaluation.answer();
long inputTokens = evaluation.usage().inputTokens();
```

An evaluation also reports `model()`, `provider()`, an optional `id()`, and an optional
`usage().cost()`. Results and exceptions both have `requestId()`, which comes from the optional
`x-typesafe-request-id` response header and has nothing to do with the `id()` in the body.
OpenRouter may leave that header out.

When a call fails, catch `JevEvaluationException` and look at `category()`: `HTTP`, `TIMEOUT`,
`IO`, `INTERRUPTED`, `MALFORMED_RESPONSE`, or `UNKNOWN`. HTTP failures also carry
`httpStatusCode()`. If you interrupt a blocking call, jev4j leaves the thread's interrupt flag set.

Exceptions from the evaluator leave out provider response bodies, API keys, and raw transport or
parser causes. Request IDs come from the provider, so treat them as provider data before you log
them. jev4j won't retry, stream, or split requests for you.

## Spring Boot

In a Spring Boot 4 application, depend on `jev4j-spring-boot-starter` in place of `jev4j-core`,
at the same version. Set your provider and key, and inject `JevEvaluator` through your
constructor:

```properties
jev.provider=openrouter
jev.api-key=${OPENROUTER_API_KEY}
```

The [starter README](jev4j-spring-boot-starter/README.md) lists the defaults and shows how to
override the beans.

## Examples

Both examples are standalone Maven projects. They run offline with synthetic answers until you set
a key and opt in to live requests:

| Example | Try |
| --- | --- |
| [Plain Java](examples/plain-java/README.md) | Thresholds, enum routing, and scores |
| [Spring Boot triage](examples/spring-boot-triage/README.md) | `POST /triage`, validation, review routing, and provider errors |

You can do the same in your own tests. `question.answer(...)` builds an answer without calling the
model:

<!-- java: body -->
```java
NoulAnswer synthetic = refundRequested.answer(0.84);
assert synthetic.isTrue();
assert !synthetic.isTrueAt(0.95);
```

## Contributing

The [contributor guide](CONTRIBUTING.md) covers development, and the
[release guide](docs/releasing.md) covers publishing.

## Further reading

- [TypeSafe API](https://docs.typesafe.ai/api)
- [Noul thresholds](https://docs.typesafe.ai/primitives/noul) and [confidence versus probability](https://docs.typesafe.ai/confidence)
- [Jev through OpenRouter](https://openrouter.ai/docs/guides/community/typesafe-sdk)

## License

[MIT](LICENSE)
