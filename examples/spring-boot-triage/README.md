# Spring Boot supermarket support triage

Try `POST /triage` in a standalone Java 17 / Spring Boot 4 application. The response includes
a queue recommendation, category probabilities, safety probability, and urgency score.

The default `offline` profile uses **synthetic fixtures**, needs no key, and makes no network calls.

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

The offline profile matches three exact messages:

| Message | Queue |
| --- | --- |
| `I was charged twice for my groceries.` | `BILLING_SUPPORT` |
| `My delivery is late and has not arrived.` | `DELIVERY_SUPPORT` |
| `How do I update my loyalty card name?` | `GENERAL_SUPPORT` |

Other messages use a low-confidence fixture: `MANUAL_REVIEW_LOW_CONFIDENCE` with queue
`MANUAL_REVIEW`. Check `answerSource` to distinguish fixtures from model output.
The demo recommends a queue; it does not perform business actions.

## Call OpenRouter

Set `OPENROUTER_API_KEY` in your environment, then enable the live profile:

```sh
./mvnw -f examples/spring-boot-triage/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=live
```

Live mode evaluates three questions in one request to `jev-latest` per valid message.
**Provider charges may apply.** The application does not log the key or customer message.
Low-confidence choices go to manual review.

Input must be nonblank and at most 1000 characters (HTTP 400 otherwise). Provider failures return
HTTP 503 with a sanitized error and no classification.

## Copy elsewhere

After installing the snapshots above, copy the whole directory, including `.mvn/jvm.config`,
and run with Maven and JDK 17:

```sh
cp -R examples/spring-boot-triage /tmp/jev4j-spring-boot-triage
cd /tmp/jev4j-spring-boot-triage
mvn verify
mvn spring-boot:run
```

To use a published library, replace `0.0.0-SNAPSHOT` in `pom.xml` with the release version.
