# Plain Java example

This is a standalone Java 17 Maven project. It has no Spring, no parent POM, and is intentionally
not a module of the repository build. Its default run uses clearly labeled synthetic local answer
data, so it is executable offline and makes no model or network call.

From the repository root, first install the library, then build and run the example:

```sh
./mvnw -pl jev4j-core -am install
./mvnw -f examples/plain-java/pom.xml verify
./mvnw -f examples/plain-java/pom.xml exec:java
```

To make real OpenRouter requests, opt in explicitly. The API key is read only from the environment
and is never logged. An optional second argument selects a Jev model (the default is
`jev-latest`; chat models do not support this endpoint):

```sh
OPENROUTER_API_KEY=... ./mvnw -f examples/plain-java/pom.xml exec:java \
  -Dexec.args="--live jev-latest"
```

No live calls are made by the tests. The example demonstrates a Noul's configured threshold and a
one-off override, a typed Choice with exhaustive switch routing, and enum-ordered Score levels with
descriptions, midpoint rounding through `acceptedLevel()`, and a low-confidence fallback.

## Copy elsewhere

After `jev4j-core:0.1.1-SNAPSHOT` is installed in your local Maven repository, copy this entire
directory anywhere and use ordinary Maven there:

```sh
cp -R examples/plain-java /tmp/jev4j-plain-java
cd /tmp/jev4j-plain-java
mvn verify
mvn exec:java
```

Run Maven with JDK 17. The copied `.mvn/jvm.config` supplies the JVM access required by Error Prone,
so copy the whole directory, including hidden files.

The example sets `maven.deploy.skip=true` to prevent accidental deployment and has no source
publication configuration. When using a released library, replace the snapshot dependency version
in `pom.xml` with that release.
