# Contributing

## Build and test

Use JDK 17 and run from the repository root. These commands need no provider key and make no model calls:

```shell
./mvnw clean install
./mvnw -f consumer-tests/pom.xml verify
./mvnw -f examples/plain-java/pom.xml verify exec:java
./mvnw -f examples/spring-boot-triage/pom.xml verify
```

[CI](.github/workflows/ci.yml) checks formatting, static analysis, public API contracts, and example
endpoints, including Java 21/25 compatibility.

## Formatting and generated code

Use Google Java Format's AOSP style. `verify` checks formatting.
Run `./mvnw fmt:format` to format the libraries; add `-f path/to/pom.xml` for a standalone project.

After changing the generated multi-question API, run:

```shell
python3 jev4j-core/generate-multi-evaluations.py
./mvnw -pl jev4j-core fmt:format
```

Commit the generated source. Repeating those commands on a clean checkout should leave no diff.

## README examples

`ReadmeCompileTest` compiles the Java fences in [README.md](README.md) with Java 17 rules; it does
not execute them. Keep the hidden `java: members` / `java: body` markers on new Java fences so the
test can assemble the shared scenario.

## Live component tests

[OpenRouter component tests](.github/workflows/openrouter-component.yml) run on `main` pushes with
the repository's API key. They make up to three paid requests, one per primitive, without retries.
To opt in locally, set `OPENROUTER_API_KEY` and run:

```shell
./mvnw -pl jev4j-core -Popenrouter-component verify
```
