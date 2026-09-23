# Publishing to Maven Central

The release workflow publishes `jev4j-parent`, `jev4j-core`, and
`jev4j-spring-boot-starter` under `io.github.maxsumrall.jev4j`.
It does not publish examples or consumer tests. Users need no custom repository
entry after Central makes the release available.

## One-time setup

1. Sign in to [Central Publisher Portal](https://central.sonatype.com/).
   Verify ownership of the `io.github.maxsumrall` namespace and generate a
   [publishing token](https://central.sonatype.org/publish/generate-portal-token/).
   Use the token's username and password, not your account password.
2. The project uses the [MIT License](../LICENSE). The root POM declares matching
   license metadata, and both library JARs include `META-INF/LICENSE`.
   Preserve these notices when preparing a release.
3. Create a passphrase-protected OpenPGP signing key. Publish its public key to a
   [Central-supported keyserver](https://central.sonatype.org/publish/requirements/gpg/).
   Back up the private key and revocation certificate outside GitHub.
4. Create a GitHub environment named **maven-central**, restricted to the `main`
   branch. Configure required reviewers and prevent self-review where supported.
   Protect `main` with required CI checks and restrict creation/deletion of `v*`
   tags with a repository ruleset. These are GitHub settings, not enforced by YAML.
5. Add these **environment secrets**:

   | Secret | Value |
   | --- | --- |
   | `CENTRAL_USERNAME` | Central token username |
   | `CENTRAL_PASSWORD` | Central token password |
   | `MAVEN_GPG_KEY` | ASCII-armored private signing key |
   | `MAVEN_GPG_PASSPHRASE` | Signing key passphrase |

The GPG plugin's Bouncy Castle signer reads the key from the environment. Do not
commit keys, tokens, or Maven settings containing credentials. Signing and Central
secrets are exposed only to the publishing step. The job has read-only GitHub
permissions and does not use a shared Maven cache.

## Prepare a release

1. Change the root version, module parent versions, and standalone test/example
   versions and library dependencies from `0.1.0-SNAPSHOT` to the release version,
   for example `0.1.0`. Set the root POM's SCM tag to `v0.1.0`. Update README
   installation instructions to the release coordinates.
2. Run the normal CI checks and inspect the unsigned release artifacts locally:

   ```shell
   ./mvnw --batch-mode --no-transfer-progress -Prelease -Dgpg.skip=true clean install
   ./mvnw --batch-mode --no-transfer-progress -f consumer-tests/pom.xml verify
   ./mvnw --batch-mode --no-transfer-progress -f examples/plain-java/pom.xml verify exec:java
   ./mvnw --batch-mode --no-transfer-progress -f examples/spring-boot-triage/pom.xml verify
   ```

   This produces source and Javadoc JARs without uploading anything. The profile
   rejects snapshot versions/dependencies and missing license properties.
3. Commit and merge the release preparation to `main`. Wait for CI and the live
   OpenRouter workflow to pass for that commit. Create and push an annotated
   `v0.1.0` tag on the commit. Do not move a published release tag.
4. In Actions, run **Publish to Maven Central** from `main`, entering `v0.1.0`.
   Approve the environment deployment after reviewing the tag's commit.

The job checks tag/version consistency and ancestry on `main`, rebuilds and tests
the tagged code, then attaches sources, Javadoc, and signatures. Sonatype's
[Central Publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/)
uploads the reactor as a bundle and waits for publication. It does not use the
retired OSSRH staging endpoints. Build timestamps use the tagged commit time.

Publication is irreversible: Central does not allow replacing a released version.
If a job times out after uploading, inspect the deployment in Central Portal before
retrying. A timeout does not prove publication failed. Fix a published defect in a
new version. After release, move `main` and standalone dependencies to the next
snapshot version in a separate commit.

Running ordinary CI, pushing a tag, or building with `-Prelease` alone does not
publish. The publishing operation is `mvn -Prelease deploy`; do not run it locally
unless you intend to publish. The workflow does not create GitHub releases or run
paid provider tests itself.
