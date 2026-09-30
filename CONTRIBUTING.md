# Contributing

Thanks for your interest in contributing to Autonomy! This document covers how
to set up the project locally, run the test suite, and submit changes.

**Maintainer:** [IamCoder18](https://github.com/IamCoder18) — open an issue or
PR on this repo for any project-related questions, and the maintainer will
follow up.

## Code of conduct

By participating, you agree to keep things respectful and constructive. We're
all here to make FTC software better.

## Project layout

This is a single-module Gradle project — the root project *is* the library:

- `src/main/java/com/aaravlabs/autonomy/` — the state framework. Plain Java:
  `State`, `AbstractState`, `StateMachine`, `WaitState`, `HoldState`. Imports
  nothing outside `java.util`.
- `src/main/java/com/aaravlabs/autonomy/ftc/` — the one robot-facing class,
  `StateMachineOpMode`. The only place Synapse, the FTC SDK, or Android may be
  named.
- `src/main/resources/META-INF/proguard/autonomy.pro` — R8 keep rules.
- `ftc-stub/src/main/java/` — hand-written compile-time stubs of the FTC SDK
  types named above. Compiled to a jar used only at compile time; see below.
- `src/test/java/com/aaravlabs/autonomy/` — JUnit 5 tests.

### Why there is an FTC stub

The real `org.firstinspires.ftc:*` artifacts are published only as Android
AARs, which a plain `java-library` project cannot place on a javac classpath.
Adopting the Android Gradle Plugin to compile one adapter class would drag
androidx and a `compileSdk` into the build and make the tests an AGP test run
instead of a fast desktop one.

So the few SDK types Autonomy names are stubbed by hand and compiled by the
`compileFtcStub` / `ftcStubJar` tasks into `build/ftc-stub/ftc-sdk-stub.jar`,
which is on the compile classpath and nowhere else. The Robot Controller
supplies the genuine classes at runtime, so the stub is `compileOnly` and never
reaches the published artifact — the POM has no trace of it.

The one rule: **stub signatures must match the real SDK exactly.** A stub that
drifts compiles cleanly and then throws `NoSuchMethodError` on a robot. If you
bump the Synapse dependency, re-read its changelog and check
`StateMachineOpModeLinkageTest`, which asserts the adapter's contract against
the real Synapse jar.

The stub jar is generated into `build/` rather than committed as a binary. The
sources are the source of truth, and a committed jar would both duplicate them
and leave the working tree dirty on every build that regenerated it.

## Building & testing

Requirements: JDK 11+, Gradle 9.x (the wrapper is checked in).

```bash
# Compile and run the tests.
./gradlew build

# Tests only.
./gradlew test

# Install into ~/.m2 for experimentation.
./gradlew publishToMavenLocal

# Publish to GitHub Packages (requires `gpr.user` + `gpr.key` or GITHUB_TOKEN).
GITHUB_USER=<your-github-username> GITHUB_TOKEN=$(gh auth token) \
    ./gradlew publishMavenPublicationToGitHubPackagesRepository
```

## Coding conventions

- **Minimal comments.** Code should speak for itself; prefer obvious naming and
  short methods over commented code. The exceptions are the *why* comments that
  record a decision someone will otherwise have to reverse-engineer.
- **The state framework stays plain Java.** No `com.qualcomm`, no `android`, no
  `com.aaravlabs.synapse` in `com.aaravlabs.autonomy`. If a class needs the
  robot, it belongs in `com.aaravlabs.autonomy.ftc`. `PackageBoundaryTest`
  enforces this by scanning bytecode, and a PR that trips it will be asked to
  move the class rather than to relax the test.
- **No Android API 26+.** The FTC SDK declares `minSdkVersion=24`, so no
  `java.time` and no `java.nio.file`. Time things with `System.nanoTime()` and
  arithmetic. `NoAndroidApiLeakTest` enforces this too.
- **No mutable static state.** It is shared across every `StateMachine` in the
  process and survives `stop()`, so one run's leftovers leak into the next.
- **States take dependencies through constructors.** A state that looks up a
  device itself cannot be unit tested, which defeats the point.
- **Tests for new behaviour.** Ordering, timing, and abort guarantees are the
  contract; a change to any of them needs a test that pins it.
- **No new runtime dependencies** without discussion. There is one today
  (Synapse), and only because `StateMachineOpMode` extends `SafeOpMode`.

## Releasing

Releases are tag-triggered. To cut one:

1. Update `version` in `build.gradle`.
2. Add a heading to `CHANGELOG.md` describing what changed.
3. Commit on `main` and push. Wait for the `Test` workflow to go green.
4. Tag: `git tag vX.Y.Z`.
5. Push: `git push origin main --tags`.

The tag push triggers the `Publish` workflow, which re-runs the tests and then
publishes to GitHub Packages. If the publish step fails, fix and re-tag with a
patch bump rather than re-running against a version that may already be
partially uploaded. The publish job is named "Publish" and runs both the
GitHub Packages and the Central step, so a failure in the first does not skip
the second; check Central's own state before re-running.

Consumers pick the new version by bumping the coordinate in their
`build.dependencies.gradle`. There is no snapshot channel, so a version that
reaches a repository is the version everyone gets.

### Maven Central

Autonomy publishes to **Maven Central** and **GitHub Packages**, in that order,
from the same tag. Central needs no credentials from consumers; the GitHub
Packages mirror exists as a fallback and does.

Central is published through the Central Portal publisher API via
`com.gradleup.nmcp.settings`, not the legacy OSSRH staging API, which stopped
accepting deployments in 2026. It is a settings plugin because the aggregation
it uploads has to be built from the root project.

Central requires a GPG signature on every artifact **and** on the Gradle module
metadata, plus mandatory sources and javadoc jars. All of that is configured;
`./gradlew nmcpZipAggregation` produces the exact bundle Central receives, so
you can inspect it before uploading:

```bash
# See what would be uploaded.
./gradlew nmcpZipAggregation
unzip -l build/nmcp/zip/aggregation.zip

# Publish to Central locally (consumes a deployment).
SONATYPE_USERNAME=… SONATYPE_PASSWORD=… \
SIGNING_KEY="$(gpg --armor --export-secret-keys)" SIGNING_PASSWORD=… \
  ./gradlew nmcpPublishAggregationToCentralPortal
```

The `signing` block in `build.gradle` signs only when **both** `SIGNING_KEY` and
`SIGNING_PASSWORD` are set, and skips signing otherwise. That is deliberate: the
GitHub Packages step gets neither, so the mirror stays unsigned, and the Central
step gets both, because Central rejects an unsigned bundle.

**The signing key must be on a PGP keyserver.** Central does not accept a
signature it cannot resolve. Publishing a bundle signed by a key that lives
only in a local keyring fails validation with:

```
Invalid signature for file: autonomy-X.Y.Z.jar.asc --
Could not find a public key by the key fingerprint.
Please ensure it is uploaded to one of the PGP servers we support.
```

Nothing is released when that happens — validation runs before the automatic
release — so the version is not burned and you can fix and re-publish. Push the
key once per new key:

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <FINGERPRINT>
```

Use **`keyserver.ubuntu.com`**, not `keys.openpgp.org`. The latter verifies
email addresses before accepting a key and silently strips the user ID from
anything it cannot verify, so a UID at `users.noreply.github.com` is dropped and
the key arrives unusable. Ubuntu's keyserver does no such verification, which
is why the sibling `com.aaravlabs` releases are signed by a key hosted there.

Secrets live in the repository's GitHub Actions secrets: `SONATYPE_USERNAME`,
`SONATYPE_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. The GPG key is a
dedicated one, generated in its own `GNUPGHOME` and used for Autonomy only.
Note that the sibling projects under `com.aaravlabs` — Synapse and Engram — are
signed by a *shared* key, whereas SafePedroPathing has its own because it is a
fork with a different maintainer identity. If you would rather Autonomy join the
`com.aaravlabs` key, this was the last point at which that could be changed,
because Central releases are immutable and 0.1.0's signer cannot be revised
afterwards.

**Decision, settled at 0.1.1:** Autonomy has its own key. The counter-argument
was that Engram -- also `com.aaravlabs`, also a separate repository -- shares
Synapse's key, so a single key per group would have been tidier. Autonomy
gets its own anyway, on the grounds that a fork of this library should not be
able to sign its releases with a key it does not hold, and because a key per
project is easier to rotate or retire independently. The cost is that
`com.aaravlabs` now has two signers, which Central does not mind.

Central mirrors to `repo1.maven.org` on a delay, usually minutes but
occasionally an hour. Search on
<https://central.sonatype.com/artifact/com.aaravlabs/autonomy> for the
authoritative release state — note that `search.maven.org`'s Solr API is stale
and can report `numFound: 0` for artifacts that are genuinely published.

## Reporting issues

Please include:

- The route that reproduces the problem, if the auto is at fault.
- Autonomy (`com.aaravlabs:autonomy:X.Y.Z`), Synapse
  (`com.aaravlabs:synapse:X.Y.Z`), and the FTC SDK
  (`org.firstinspires.ftc:RobotCore:X.Y.Z`) versions.
- What you expected vs. what happened, and the Driver Station telemetry if the
  auto got far enough to print any.

Issues go at <https://github.com/IamCoder18/autonomy/issues>.
