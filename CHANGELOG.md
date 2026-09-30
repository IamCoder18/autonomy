# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.1] - 2026-09-30

No source changes from 0.1.0 -- same classes, same tests, same behaviour. This
version exists because of how the library is *distributed*.

### Changed

- **Published to Maven Central**, alongside the GitHub Packages mirror. Central
  is reached through the Central Portal publisher API (`com.gradleup.nmcp.settings`)
  because the legacy OSSRH staging API stopped accepting deployments in 2026.
  Central consumers need no credentials, which makes it the path of least
  resistance; the GitHub Packages mirror remains as a fallback.
- **Artifacts are now GPG-signed**, because Central requires a signature on
  every artifact and on the Gradle module metadata. Signing is conditional on
  both `SIGNING_KEY` and `SIGNING_PASSWORD` being set, so nothing else in the
  build changes when they are absent.
- **0.1.0 is not superseded in place.** It stays on GitHub Packages, unsigned.
  Reusing that version number for a signed build would give one version two
  different sets of bytes depending on where a consumer found it, so this is a
  new coordinate rather than a republish.

### Notes

- This is a patch bump, not a minor one. Nothing in the public API moved and no
  class changed, so a consumer on 0.1.0 needs no code edits; what changed is how
  the artifact is distributed and signed. A minor bump would imply a capability
  the library does not have.

## [0.1.0] - 2026-09-30

First release. The state machine was extracted from the Team 23684 robot code
so it could be versioned, tested, and reused independently of the FTC SDK fork
it was written in.

### Added

- **`State`** — the four-method step contract: `init()`, `loop()`, `stop()`,
  and a `BooleanSupplier` end condition. `name()` defaults to the simple class
  name for telemetry.
- **`AbstractState`** — base class that stores the end condition, with
  `setEndCondition(...)` called from `init()`. A state that never sets one loops
  exactly once and then finishes, so a forgotten condition moves the routine on
  instead of stalling it.
- **`StateMachine`** — runs a fixed `List<State>` in order, one OpMode iteration
  per `update()`. The end condition is read *after* `loop()`, so every state
  loops at least once. `stop()` ends the route early and runs the active
  state's `stop()`. Reports `currentState()`, `currentIndex()`,
  `elapsedSeconds()`, and `currentStateElapsedSeconds()` for tuning. Injects a
  `LongSupplier` clock so tests are deterministic.
- **`WaitState`** — waits a fixed number of seconds. Defaults to the label
  "Wait"; a named constructor is available for a settle delay.
- **`HoldState`** — holds a mechanism for a fixed time (`forSeconds`) or until a
  condition (`until`), then releases it. The action is a `Consumer<Boolean>`
  rather than a `Runnable` so that releasing belongs to the state and a motor
  cannot be left running after the routine moves on.
- **`StateMachineOpMode`** (`com.aaravlabs.autonomy.ftc`) — `OpMode` base class
  extending Synapse's `SafeOpMode`. A subclass implements `buildStates()` and
  nothing else; the base class runs the machine, calls the active state's
  `stop()` when the OpMode is interrupted, and reports `Auto`, `State`,
  `State time`, and `Auto time` to the Driver Station.
- **R8 keep rules** at `META-INF/proguard/autonomy.pro`, for teams that build a
  minified release APK.

### Changed

- **Repackaged** from `org.firstinspires.ftc.teamcode.autonomy` to
  `com.aaravlabs.autonomy`. Class names, method signatures, and behaviour are
  unchanged; this is a source-level rename only. Migrating means editing the
  `package` line and your imports.
- **Reformatted** from tabs to four spaces, matching Synapse and Engram.
  No behavioural change.

### Notes

- Behaviour is unchanged from the in-tree version. The five original test
  classes moved with the code and still pass unchanged, which is the reason to
  believe the extraction was behaviour-preserving.
- Four guard tests were added that did not exist in the team repo:
  `NoAndroidApiLeakTest` (Android API 24 floor), `PackageBoundaryTest` (the core
  stays plain Java), `StateMachineOpModeLinkageTest` (the adapter still matches
  the Synapse release it compiles against), and `SdkReferenceTest` (every FTC SDK
  member the compiled adapter references is one RobotCore 12.0.0 actually
  declares).
- `SdkReferenceTest` earned its keep during the extraction. The first draft of
  the `Telemetry` stub declared `addData(String, Object, Object...)`; the real
  SDK declares `addData(String, String, Object...)`, with the format parameter
  typed `String`. The call sites compiled, every other test passed, and the
  published jar carried a `Methodref` for an overload that does not exist — a
  `NoSuchMethodError` on the first telemetry update of a match. The stub also
  now mirrors the real hierarchy, where `OpMode` extends the package-private
  `OpModeInternal` that declares `telemetry` and `hardwareMap`.
- **SDK baseline is 12.0.0.** Every class the FTC stub covers -- `OpMode`,
  `OpModeInternal`, `Telemetry`, `Telemetry.Item`, `HardwareMap`, `Gamepad` --
  is byte-for-byte identical between SDK 11.2.1 and 12.0.0, verified by diffing
  `javap` output across both AARs. The bump is therefore a label change, but the
  next one will not be, so `SdkReferenceTest`'s allowlist is pinned to a specific
  SDK version and named after it.
- Published to Maven Central *and* GitHub Packages from the same tag. Central is
  reached through the Central Portal publisher API (`com.gradleup.nmcp.settings`)
  because the legacy OSSRH staging API stopped accepting deployments in 2026.
  Central consumers need no credentials; the GitHub Packages mirror is a
  fallback for setups that already authenticate against GitHub.
- Signed with a dedicated GPG key, because Central requires a signature on every
  artifact and on the Gradle module metadata. Signing is conditional on both
  `SIGNING_KEY` and `SIGNING_PASSWORD` being set, so the GitHub Packages
  publish stays unsigned and only the Central bundle is signed.
