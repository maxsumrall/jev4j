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
   versions and library dependencies from the current `-SNAPSHOT` version to the release version,
   for example `0.1.0`. Set the parent and both library POMs' SCM tags to `v0.1.0`. Update README
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
3. Commit on a preparation branch and promote the checked commit to `main` using
   the fast-forward procedure below. Wait for CI and the live
   OpenRouter workflow to pass for that commit. Obtain explicit approval of the exact
   version and full commit SHA, then create and push an annotated `v0.1.0` tag on
   that commit. Do not move a published release tag.
4. In Actions, run **Publish to Maven Central** from `main`, entering `v0.1.0`
   and the approved full commit SHA.
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

## First-release prerequisite checks

The workflow requires both the existing release tag and the approved full commit
SHA. It rejects a tag pointing elsewhere and requires successful `push` runs on
`main` of `ci.yml` and `openrouter-component.yml` for that exact commit. The gate
checks the latest matching run of each workflow and verifies the returned SHA, branch, event, status, and conclusion. Missing runs, pending/failed/cancelled
runs, malformed responses, and API/permission errors all stop publication. The
GitHub token has only `contents: read` for checkout and `actions: read` for these
workflow-run queries; Central publishing uses its separate token. Review both
the workflow on the dispatch ref and the code at the release tag.

### Portal account and token

Sign in at <https://central.sonatype.com> using the `maxsumrall` GitHub account.
In Namespaces, confirm `io.github.maxsumrall` is **Verified**; it covers the
`io.github.maxsumrall.jev4j` group. GitHub sign-up normally provisions the username
namespace automatically. If it is missing, follow the Portal verification flow
or contact Central support; do not assume ownership from the group ID alone.
At <https://central.sonatype.com/usertoken>, generate a named publishing token
with an expiration and save it in a password manager. Its generated **username**
and **password** are `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`. Neither value is
your GitHub login, GitHub PAT, or Sonatype account password. Never put values in
chat, shell command arguments, repository files, or logs.

Before continuing, confirm privately that both token fields and the final expiration
are saved in your password manager. Only report that they are saved; never send
their values in chat. A visible active token does not prove its credentials have
been backed up or tested.

### Local signing key

Use GnuPG on your own machine, never an orb or a remote development workspace.
Install GnuPG if needed, then inspect existing keys locally:

```shell
gpg --list-secret-keys --keyid-format LONG --with-subkey-fingerprint
```

Reuse a key only if you control it, it has a usable signing component, is not
expired or revoked, and is passphrase-protected. Otherwise run
`gpg --full-generate-key` interactively. A dedicated RSA 4096 signing-capable key
with a one-year expiration is a conservative choice. Enter your chosen public
identity and a strong passphrase through the interactive prompts. Do not put the
passphrase on a command line. Do not remove subkeys from an existing identity.

Use the full fingerprint in place of `YOUR_FULL_FINGERPRINT` below. Keyservers
publish the public key and its identity; confirm the name/email before sending.
Never send the private key to a keyserver.

```shell
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys YOUR_FULL_FINGERPRINT
```

Check retrieval from an independent temporary public keyring and compare the
full fingerprint. Central must be able to retrieve the signing public key.
Keep an encrypted private-key backup and the generated revocation certificate
(`openpgp-revocs.d` under your GnuPG home) in offline storage or a suitable secret
manager, outside the repository. Treat the revocation certificate as sensitive:
it can invalidate the key. Test restoration privately. Keep the passphrase in a
separate password-manager entry. Set a reminder before expiration, extend both
primary/signing-key expiration as applicable, and republish the updated public
key. If compromised, revoke and publish the revocation, rotate the GitHub key
and passphrase secrets, and use a new key for future releases.

### Environment protection and secrets

Before changing shared settings, agree on the protection configuration with the
repository owner. Proposed configuration: environment `maven-central`, selected
**branch** `main` only (no tag deployment rule), required trusted reviewer, and
administrator bypass disabled where available. The workflow is dispatched from
`main` and subsequently checks out the tag. Prevent self-review only when another
trusted reviewer is available; a solo maintainer cannot approve their own run
with that option enabled. Main-branch and release-tag rules require separate
approval and should prevent unreviewed workflow changes and tag movement.

GitHub Free/Pro/Team required environment reviewers are available only for
public repositories. Pro permits private environments/secrets/branch policies,
but does not add required reviewers for private repositories. Private required
reviewers need an eligible Enterprise setup. Do not make the repository public
or buy/upgrade a plan without owner approval. A manual dispatch alone is not an
equivalent enforced reviewer gate. If the plan cannot enforce the requested
protection, stop and agree on the protection model before adding production keys.

Available protection choices (none is selected automatically):

| Choice | Protection actually provided |
| --- | --- |
| Public repository on a current GitHub plan | Environment required reviewers and branch restrictions; exposes repository contents/history publicly. Requires explicit visibility approval. |
| Private repository in an eligible Enterprise setup | Private environment reviewer gate and branch restrictions; may require an organization/plan change. Requires explicit approval. |
| Private repository with Pro/Team only | Environment secrets and branch restrictions, but no required-reviewer gate. Does not meet the requested approval protection by itself. |
| Keep current setup and pause publication | No new access or spend; no production secrets added until a suitable gate is available. |

A local, owner-operated release or an independently gated secret manager could
be designed separately if requested; neither is silently substituted for the
requested GitHub approval gate. Manual dispatch, a SHA input, and passing CI are
not human approval protection. Do not dispatch even as a setup test: GitHub can
create a missing environment without any protection rules. Verify the configured
reviewer gate, main-only branch policy, and bypass settings before adding secrets.

### Approved repository protection and commit promotion

The owner approved making this repository public. The applied protection is:

- `main` requires `build-java-17`, `test-java-compatibility (21)`, and
  `test-java-compatibility (25)`, each pinned to GitHub Actions app ID `15368`.
  These names and app identity were verified from actual repository check runs.
  Up-to-date checks, linear history, and administrator enforcement are enabled;
  force pushes and branch deletion are disabled. There is no required PR gate.
- Two active `refs/tags/v*` rulesets restrict creation to administrators and
  prohibit updates/deletion without bypass actors.
- `maven-central` permits only branch `main`, requires review by `maxsumrall`,
  and disables administrator bypass. Self-review is allowed as approved for a
  sole maintainer. This is a manual approval gate, not two-person approval.

Recheck these settings before releasing. GitHub supports a direct push after
[required status checks pass](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).
Preserve the checked commit and its identity using this procedure:

1. Fetch current `origin/main`. Base preparation work on that history; never merge
   an old-history branch. Set both author and committer to
   `Max Sumrall <jmsumrall@gmail.com>`, with no tool attribution or thread trailers.
2. Commit and push a preparation branch. Require all three exact checks above to
   complete successfully from app `15368` on that full commit SHA. Do not accept
   skipped or neutral results as release evidence.
3. Fetch `origin/main` again and confirm it is an ancestor of the checked SHA and
   that the new history contains no merge commits. If main advanced incompatibly,
   replay only the preparation patch onto current main and obtain fresh checks.
4. Push the checked SHA directly to `refs/heads/main` without force. This changes
   the branch pointer without changing the commit SHA, author, or committer. If
   rejected, stop and investigate; never weaken checks or use a bypass.
5. Read back main's SHA and wait for its new CI and live OpenRouter push runs to
   succeed on that exact SHA. OpenRouter runs on main, so it is a publication
   prerequisite after promotion, not a required preparation-branch check.

Do not amend, squash, or rebase a checked commit and reuse its previous checks.
No preparation push authorizes creating a release tag or dispatching publication.

### Enter environment secrets locally

Once the protected environment exists, use repository Settings → Environments →
`maven-central` → Environment secrets. Enter the four values directly there, or
run the following in your own terminal (interactive input is hidden):

```shell
gh secret set CENTRAL_USERNAME --repo maxsumrall/jev4j --env maven-central
gh secret set CENTRAL_PASSWORD --repo maxsumrall/jev4j --env maven-central
gh secret set MAVEN_GPG_PASSPHRASE --repo maxsumrall/jev4j --env maven-central
```

For the key, use a local pipeline so the armored private key is not printed or
written to a repository file. Run in Bash with tracing disabled. GnuPG may prompt
locally for the passphrase. Export only the selected dedicated publishing key.

```bash
set +x
set -o pipefail
gpg --armor --export-secret-keys YOUR_FULL_FINGERPRINT |
  gh secret set MAVEN_GPG_KEY --repo maxsumrall/jev4j --env maven-central
```

Verify names and policy without reading secret values:

```shell
gh secret list --repo maxsumrall/jev4j --env maven-central
gh api repos/maxsumrall/jev4j/environments/maven-central
gh api repos/maxsumrall/jev4j/environments/maven-central/deployment-branch-policies
```

Listing names proves storage/access to metadata, not token validity or successful
signing. GitHub does not return stored secret values.

### Non-publishing validation and approval record

After the license and release versions are committed, run the unsigned release
commands above. For a real local signing check, run the following in your own
Bash terminal with the selected key and an interactive hidden passphrase prompt.
The subshell removes the environment variables when it exits. Do not use Maven
`-X`, shell tracing, or a captured terminal session with secrets loaded.

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

Inspect both libraries' main/source/Javadoc JARs and the parent POM. Verify every
`.asc` against its corresponding artifact/POM with `gpg --verify`. The parent
uses POM packaging and does not need source/Javadoc JARs. The Central plugin
creates bundle checksums at deployment; local `verify` does not exercise upload,
server-side validation, credential validity, or publication. Do not run `deploy`
as a dry run. Even manual-mode Central validation requires an upload and separate
approval in this first-release process.

Before approval, record:

- Chosen license text and resolved POM license name/URL; inherited developer,
  description, project URL, SCM connection and release tag in effective POMs.
- Coordinated root/module-parent versions, standalone example/test versions and
  dependencies, and README coordinates across all READMEs. No SNAPSHOT release.
- Exactly three coordinates: parent POM, core, optional-to-consumers starter.
  Examples and consumer tests stay outside the reactor with deployment disabled.
- Source/Javadoc content, signatures, public fingerprint retrieval, key/token
  expiry, successful unsigned and signed checks, and secret names/protection.
- Full release commit SHA, `vX.Y.Z` SCM tag, clean working tree, successful CI
  including Java 17/21/25 consumer checks and live component checks on that SHA.
- Explicit owner approval of **version and commit** before pushing the annotated
  release tag or dispatching the publishing workflow.

After that approval, the dispatch command is:

```shell
gh workflow run release.yml --repo maxsumrall/jev4j --ref main \
  -f tag=vX.Y.Z -f commit=APPROVED_FULL_COMMIT_SHA
```

The environment approval releases the job. Its `./mvnw -Prelease deploy` invokes
Central with `autoPublish=true` and `waitUntil=published`: upload, validation,
and irreversible publication are one operation. Do not approve until tag and SHA
match the approval record.

### Ambiguous timeout recovery

Do not rerun a timed-out publishing job automatically. Retain the deployment ID
and inspect the Central Portal deployment and all three coordinates first. A
`PUBLISHING` deployment needs time; `PUBLISHED` must never be uploaded again.
A `VALIDATED` manual deployment may need explicit Portal publication approval;
`FAILED` requires inspecting validation errors. For an unresolved upload with no
clear deployment record, contact Central support before retrying. Only retry an
unpublished version after confirming the prior deployment cannot still publish
and resolving/deleting that deployment as appropriate. Central versions are
immutable; published mistakes require a new version, never retagging/replacing.

References: [Central namespace verification](https://central.sonatype.org/register/namespace/),
[Portal tokens](https://central.sonatype.org/publish/generate-portal-token/),
[Central requirements](https://central.sonatype.org/publish/requirements/),
[public signing keys](https://central.sonatype.org/publish/requirements/gpg/),
[Maven GPG signer](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html),
and [GitHub environment protection availability](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).
