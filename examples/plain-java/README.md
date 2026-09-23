# Plain Java example

Try Noul thresholds, enum routing, and scores in a standalone Java 17 project.
The default run and tests use synthetic answers, with no credentials or network calls.

From the repository root:

```sh
./mvnw -pl jev4j-core -am install
./mvnw -f examples/plain-java/pom.xml verify exec:java
```

## Call OpenRouter

Set `OPENROUTER_API_KEY` in your environment, then opt in to three model requests.
**Provider charges may apply.**

```sh
./mvnw -f examples/plain-java/pom.xml exec:java \
  -Dexec.args="--live jev-latest"
```

The model argument defaults to `jev-latest`. Use a Jev model, not a chat model.

## Copy elsewhere

After installing the snapshot above, copy the whole directory, including `.mvn/jvm.config`,
and run with Maven and JDK 17:

```sh
cp -R examples/plain-java /tmp/jev4j-plain-java
cd /tmp/jev4j-plain-java
mvn verify exec:java
```

To use a published library, replace `0.0.0-SNAPSHOT` in `pom.xml` with the release version.
