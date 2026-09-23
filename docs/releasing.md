# Publishing to Maven Central

Use [release.yml](../.github/workflows/release.yml) to publish `jev4j-parent`, `jev4j-core`, and
`jev4j-spring-boot-starter` under `io.github.maxsumrall.jev4j`. Examples and consumer tests stay out
of the release. **Publication is irreversible.**

## Run a release

1. [Promote a checked commit](#promote-a-checked-commit) to `main`. Wait for its CI and OpenRouter
   push runs to pass.
2. Choose an unused CalVer version and dispatch **Publish to Maven Central** from `main`:

   ```shell
   gh workflow run release.yml --repo maxsumrall/jev4j --ref main -f version=2026.9.1
   ```

3. Check the version and full source SHA in the run name before approving the `maven-central`
   environment gate. Approval authorizes the tag and publication. GitHub pins the source SHA at
   dispatch; later pushes do not enter that release. Cancel and start a new dispatch if it is wrong.
4. After approval, the workflow substitutes POM versions in its checkout, verifies CI, builds and
   tests, then reserves an annotated `vVERSION` source tag before signing and publishing.

Do not create the tag yourself or dispatch a release as a setup test. The Central plugin uses
`autoPublish=true` and `waitUntil=published`: upload, validation, and publication form one operation.
Ordinary CI and tag pushes do not publish. The workflow creates no version-bump commit or GitHub Release.

## Version policy

Keep the six development POMs on `0.0.0-SNAPSHOT`, with SCM tags set to `HEAD`. Release tags point
to those source commits; CI puts the release version and SCM tag into published POMs and JARs.
Update README installation coordinates after confirming availability on Central.

Use **CalVer `YYYY.M.N`**: UTC year, month, and a positive sequence number within that month,
without leading zeroes. For example: `2026.9.1`, `2026.9.2`, `2026.10.1`. Choose a number higher
than previous reservations that month; leave failed reservations in place. The workflow checks
format, not today's date or the next available number. CalVer indicates release order, not API
compatibility; document breaking changes.

## Promote a checked commit

`main` requires these GitHub Actions checks, pinned to app ID `15368`:

- `build-java-17`
- `test-java-compatibility (21)`
- `test-java-compatibility (25)`

The repository requires up-to-date checks and linear history, enforces rules for administrators,
and blocks force pushes and branch deletion. It has no required PR gate.

1. Fetch `origin/main` and base your changes on it. Use `Max Sumrall <jmsumrall@gmail.com>` as
   author and committer, without tool attribution or thread trailers.
2. Commit and push a preparation branch. Wait for all three checks to succeed on that exact SHA
   from app `15368`; skipped and neutral results do not qualify.
3. Fetch again. Confirm `origin/main` is an ancestor of the checked SHA and the new history has
   no merge commits. If you must rebase, obtain fresh checks for the new SHA.
4. Push the checked SHA to `refs/heads/main` without force. If GitHub rejects it, investigate
   without weakening checks or using a bypass.
5. Read back main's SHA and wait for its CI and live OpenRouter push runs to pass on that SHA.

Amending, squashing, or rebasing changes the commit and invalidates its check evidence.
Promotion does not authorize a release dispatch or tag creation.

## Setup and credentials

Recheck repository protections before releasing:

- Two `refs/tags/v*` rulesets restrict creation to administrators and block updates/deletion
  without bypass actors.
- The `maven-central` environment permits branch `main`, requires review by `maxsumrall`, and
  disables administrator bypass. The approved solo-maintainer setup permits self-review; it
  provides manual approval, not two-person approval.

These controls live in GitHub settings, not YAML. Obtain owner approval before changing them.
Verify the gate and branch policy before adding secrets; do not use a release dispatch to test setup.

Sign in to [Central](https://central.sonatype.com/) with the `maxsumrall` GitHub account. Confirm
the `io.github.maxsumrall` namespace is **Verified**, then create a [publishing token](https://central.sonatype.com/usertoken).
Save its generated username, password, and expiry in a password manager.

Prepare a passphrase-protected OpenPGP signing key on your own machine, not in an orb. Use a key
you control with a valid signing component. Publish the **public key** to a
[Central-supported keyserver](https://central.sonatype.org/publish/requirements/gpg/) and verify
retrieval against its full fingerprint. Back up the private key and revocation certificate outside
the repository; keep the passphrase separate and track key/token expiry.

Add five secrets to the protected `maven-central` environment:

| Secret | Value |
| --- | --- |
| `CENTRAL_USERNAME` | Publishing token username, not your account login |
| `CENTRAL_PASSWORD` | Publishing token password |
| `MAVEN_GPG_KEY` | ASCII-armored private signing key |
| `MAVEN_GPG_PASSPHRASE` | Signing key passphrase |
| `RELEASE_TAG_TOKEN` | Owner's repository-scoped token with Contents read/write for protected tag creation |

Use GitHub's environment settings or these commands in your own terminal. Keep secret values out
of chat, command arguments, files in the repository, and logs:

```shell
gh secret set CENTRAL_USERNAME --repo maxsumrall/jev4j --env maven-central
gh secret set CENTRAL_PASSWORD --repo maxsumrall/jev4j --env maven-central
gh secret set MAVEN_GPG_PASSPHRASE --repo maxsumrall/jev4j --env maven-central
gh secret set RELEASE_TAG_TOKEN --repo maxsumrall/jev4j --env maven-central
```

Export the selected publishing key through a local Bash pipeline, with tracing disabled:

```bash
set +x
set -o pipefail
gpg --armor --export-secret-keys YOUR_FULL_FINGERPRINT |
  gh secret set MAVEN_GPG_KEY --repo maxsumrall/jev4j --env maven-central
```

Verify secret names and protection settings with `gh secret list` and GitHub's environment API.
Stored names do not prove credential validity. The workflow gives `GITHUB_TOKEN` read access,
exposes `RELEASE_TAG_TOKEN` to the tag step, and exposes signing/Central secrets to the publishing step.

## Validate without publishing

Use a disposable checkout of the source commit with JDK 17. Substitute the chosen version below:

```shell
python3 .github/release-version.py 2026.9.1
./mvnw --batch-mode --no-transfer-progress -Prelease -Dgpg.skip=true clean install \
  -Dproject.build.outputTimestamp="$(git show -s --format=%ct HEAD)"
./mvnw --batch-mode --no-transfer-progress -f consumer-tests/pom.xml verify
./mvnw --batch-mode --no-transfer-progress -f examples/plain-java/pom.xml verify exec:java
./mvnw --batch-mode --no-transfer-progress -f examples/spring-boot-triage/pom.xml verify
```

Do not commit the modified POMs. These commands build unsigned artifacts without uploading.
For a signing check, run this in your own Bash terminal after preparing those POMs. Avoid terminal
recording, shell tracing, and Maven debug logging while secrets are loaded:

```bash
(
  set +x
  export MAVEN_GPG_KEY="$(gpg --armor --export-secret-keys YOUR_FULL_FINGERPRINT)"
  test -n "$MAVEN_GPG_KEY" || exit 1
  read -r -s -p 'Signing passphrase: ' MAVEN_GPG_PASSPHRASE
  printf '\n'
  export MAVEN_GPG_PASSPHRASE
  ./mvnw --batch-mode --no-transfer-progress -Prelease clean verify
)
```

Check the parent POM and both libraries' main/source/Javadoc JARs. Confirm release coordinates,
SCM tags, and MIT license notices, including `META-INF/LICENSE`. Verify each `.asc` against its
artifact with `gpg --verify`. Record the version, source SHA, signing fingerprint, and check results
before approving publication.

Local verification does not test Central credentials or server-side validation. **Do not use
`deploy` as a dry run.** Any upload needs separate approval.

## Failed or ambiguous releases

The workflow rejects reruns, existing version tags, and Central POM URLs that do not return 404.
It also requires the latest `ci.yml` and `openrouter-component.yml` push runs on `main` to succeed
for the pinned SHA. Network/API errors stop publication. Tag creation reserves the version before
upload; a Central 404 alone cannot rule out a pending deployment.

After a timeout or failure, inspect the run, reserved tag, and Central deployment before trying again:

- **No tag or upload:** fix the failure and start a new dispatch.
- **Tag exists:** leave it in place, even if signing or publication failed. Choose a new version.
- **Upload status unclear:** retain the deployment ID and inspect Portal and all three coordinates.
  Wait while the status is `PUBLISHING`; do not re-upload `PUBLISHED`. Inspect errors for `FAILED`.
  A manual `VALIDATED` deployment needs publication approval. Contact Central support if no clear
  deployment record exists.

Resolve an existing deployment before starting another release. Published versions are immutable;
fix mistakes with a new version, not a moved tag or replacement upload.

References: [Central requirements](https://central.sonatype.org/publish/requirements/),
[publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/),
[GPG signer](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html).
