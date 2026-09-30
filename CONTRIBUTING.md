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
partially uploaded.

Consumers pick the new version by bumping the coordinate in their
`build.dependencies.gradle`. There is no snapshot channel, so a version that
reaches GitHub Packages is the version everyone gets.

### Maven Central, later

Autonomy publishes to GitHub Packages only, and that is deliberate: Maven
Central releases are permanently immutable, which is a poor fit for a library
that will move weekly through a competition season. Central also requires GPG
signatures on every artifact plus mandatory sources and javadoc jars.

The `signing` block in `build.gradle` is already wired for it, so promoting
Autonomy later is additive — publish `X.Y.Z` to Central without disturbing the
`0.1.x` GitHub Packages line. Nothing needs retrofitting.

## Reporting issues

Please include:

- The route that reproduces the problem, if the auto is at fault.
- Autonomy (`com.aaravlabs:autonomy:X.Y.Z`), Synapse
  (`com.aaravlabs:synapse:X.Y.Z`), and the FTC SDK
  (`org.firstinspires.ftc:RobotCore:X.Y.Z`) versions.
- What you expected vs. what happened, and the Driver Station telemetry if the
  auto got far enough to print any.

Issues go at <https://github.com/IamCoder18/autonomy/issues>.
