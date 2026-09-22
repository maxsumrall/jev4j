# jev4j

Type-safe Java contracts for asking probabilistic questions. This repository currently contains
only the immutable core API; transport and evaluation are deliberately deferred.

```java
var question = Jev.noul("Will it rain today?").describe(true, "It rains today").threshold(0.7);
// Synthetic local data for illustration; answer(...) does not evaluate the question remotely.
var answer = question.answer(0.8);
boolean accepted = answer.isTrue();
```

Choice and score declarations are also type-safe:

```java
enum Weather { SUN, RAIN }
var choice = Jev.choice(Weather.class, "Choose the expected weather")
    .answer(Weather.RAIN, Map.of(Weather.SUN, 0.4, Weather.RAIN, 0.6), 0.9);

var score = Jev.score("Rate the result").level("poor").level("excellent").build()
    .answer(0.75, List.of(0.2, 0.8), 0.9);
```

These examples are compile-checked in the test suite. The build is verified with Java 17:

```shell
./mvnw verify
```
