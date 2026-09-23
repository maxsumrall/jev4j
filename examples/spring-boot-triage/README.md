# Spring Boot supermarket support triage

A copyable Java 17 / Spring Boot 4 REST example using
`io.github.maxsumrall.jev4j:jev4j-spring-boot-starter:0.1.0-SNAPSHOT`. It is a standalone Maven
project: it has no repository parent and is not a root reactor module.

The default `offline` profile makes no network calls and needs no API key. It returns clearly
labeled, deterministic **synthetic fixtures** for the three exact messages below; any other message
gets the synthetic low-confidence fixture and manual review. This is a runnable integration demo,
not an NLP classifier and not real model output.

From the repository root:

```sh
./mvnw -pl jev4j-spring-boot-starter -am install
./mvnw -f examples/spring-boot-triage/pom.xml verify
./mvnw -f examples/spring-boot-triage/pom.xml spring-boot:run
```

In another terminal:

```sh
curl --fail-with-body -sS http://localhost:8080/triage \
  -H 'Content-Type: application/json' \
  -d '{"message":"I was charged twice for my groceries."}'
```

Other fixture messages are `My delivery is late and has not arrived.` and
`How do I update my loyalty card name?`. The response identifies `answerSource`, reports the typed
Choice probabilities and confidence, and includes Noul safety probability and Score urgency. The
Choice is routed with an exhaustive enum `switch`. Low confidence produces the distinct
`MANUAL_REVIEW_LOW_CONFIDENCE` decision and `MANUAL_REVIEW` queue even when the model category is
`OTHER`. This demo only recommends a queue; it never refunds, updates an order, or performs another
business action.

## Explicit live opt-in

The `live` profile enables the starter's OpenRouter evaluator and uses `jev-latest`. It can incur
provider charges and performs three evaluations per request. The key and customer message are not
logged by this application. Do not put the key in source or command arguments:

```sh
export OPENROUTER_API_KEY='...'
./mvnw -f examples/spring-boot-triage/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=live
```

Provider failures return HTTP 503 with a sanitized error and no guessed classification. Input must
be nonblank and at most 1000 characters; invalid input returns HTTP 400.

## Copy elsewhere

After installing the snapshot starter and core into the local Maven repository, copy this entire
directory and run ordinary Maven. For a released artifact, replace the snapshot version in
`pom.xml`.

```sh
cp -R examples/spring-boot-triage /tmp/jev4j-spring-boot-triage
cd /tmp/jev4j-spring-boot-triage
mvn verify
mvn spring-boot:run
```

Run Maven with JDK 17. The copied `.mvn/jvm.config` supplies the JVM access required by Error Prone,
so copy the whole directory, including hidden files.
