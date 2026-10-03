# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **States nest to any depth.** `Submachine` holds a list of states and runs them
  in order; groups go inside groups, as deep as a route is built. A group ends
  when its last child ends, and entering or leaving one is a single transition —
  a state three groups deep is retired in the same one `update()` as one at the
  top level, so structuring a route never costs latency.

  ```java
  new Submachine("RightScissor", Arrays.asList(
          new Submachine("Drive", forward(), strafe()),
          new Submachine("Turn",  turnAway())));
  ```

  A third constructor takes a trailing condition, which leaves the group early —
  "do this phase until we see the goal" — and stops the state the group was
  running first, so nothing is left held. Subclassing overrides `init()`/`stop()`
  for entry and exit work.

  `State` is deliberately unchanged: a group *is* a state, recognised by the
  runner, so a team that implements `State` directly is unaffected and nothing
  source-breaks. `Submachine` implements `State` rather than extending
  `AbstractState`, because both of `AbstractState`'s defaults are wrong for a
  group — its run-once fallback would end every group after one iteration, and
  `isEndConditionDefaulted()` would report every group to the Driver Station as a
  state that forgot its end condition.

  `StateMachine` gained `stepCount()` / `currentStepIndex()` (every step, however
  nested, beside the existing top-level `size()` / `currentIndex()`), plus
  `depth()` and `path()`. `StateMachineOpMode` shows the step and the enclosing
  phase as extra telemetry lines. Aborting a nested route now runs `stop()` on
  every group it was inside, innermost first, so a group that raised a mast on
  entry lowers it on the way out.

- **`StateMachineOpMode` can be hooked at INIT and START without overriding the
  Synapse lifecycle.** Two no-op hooks, `onRouteInited()` and `onRouteStarted()`,
  both `protected` and parameterless like `buildStates()`: the first fires during
  INIT after the route has been built, the second when START is pressed after the
  first state has been entered, so `currentState()` is meaningful in it.

  This is what makes sealing the lifecycle hooks possible, which see *Changed*.

### Fixed

- **A finished autonomous never ended its OpMode.** `StateMachineOpMode` ran the
  route, reported telemetry, and stopped — it never asked the Robot Controller to
  end the OpMode. The SDK drives an OpMode as `while (!stopRequested) { loop();
  sleep(1); }` and reaches `stop()` only once something raises that flag, so an
  auto that ran out of states kept looping until the Robot Controller force-killed
  it at the match timer. On the Driver Station: the OpMode stays RUNNING with
  telemetry frozen on `Auto: DONE`. Hardware was already released by the last
  state's `stop()`, which is exactly why it was easy to miss — the robot looks
  idle and correct and the auto simply never finishes.

  `onSafeLoop()` now calls `requestOpModeStop()` once the route is exhausted, and
  falls out of the SDK's loop into a normal `stop()` rather than taking the
  force-stop path `terminateOpModeNow()` uses. Covered by
  `StateMachineOpModeEndsItselfTest` for the control flow and by
  `SdkReferenceTest` for the descriptor.

- **`@Autonomous`'s and `@TeleOp`'s compile-time stubs did not match the SDK.**
  `Autonomous.preselectTeleOp()` was declared `int` where the SDK declares
  `String`, and both stubs carried three members lifted from `LinearOp` that the
  real annotations do not declare. Nothing shipped was affected — Autonomy does
  not use these annotations and the stub is `compileOnly` — but `@TeleOp` is what
  a team writes first, so this was one compiler error away from a published
  `NoSuchMethodError`. Transcribed from the AAR.

- **`HardwareMap.get(String)` was stubbed as returning `Object`** where the SDK
  declares `HardwareDevice` — another descriptor mismatch of the 0.1.2 kind.
  Added a minimal `HardwareDevice` stub, deliberately memberless so it can never
  be more permissive than the real interface.

- **A state that never set an end condition was skipped silently.** It runs once
  on the documented run-once default and the route moves on, which is deliberate,
  but it is the same shape as the "state got skipped" bug this library exists to
  design out. `AbstractState.isEndConditionDefaulted()` exposes it and
  `StateMachineOpMode` reports it as a `State problem` telemetry line, recorded
  before `update()` (the state is already gone afterwards) and kept for the rest
  of the run.

- **`HoldState` powered its mechanism during INIT, and a short hold could be
  entirely consumed before the robot moved.** Both stemmed from anchoring the
  work in `init()`. `init()` runs while the Driver Station shows INIT, before the
  driver has pressed START, so the intake was already spinning on the bench and
  before the match was live -- at best surprising, at worst the reason a robot
  grabs a wall. Worse, the deadline was armed at the same moment: a 0.75 s settle
  delay, or a 0.2 s shoot, could be spent entirely waiting for the driver, so the
  step did not happen at all.

  `HoldState` now applies its action on the first `loop()` -- the first iteration
  the Robot Controller runs with the OpMode started -- and still only once, which
  keeps the per-iteration write amplification away. `WaitState` arms its deadline
  there too. Both measure from when the OpMode actually runs, so time in INIT
  costs nothing. (A `State` cannot know when START was pressed — it is driven by a
  bare `StateMachine` just as often — so this remains the right place for them even
  now that `StateMachineOpMode` enters its route on START; see *Changed*.)

  `HoldState.until(...)` was already immune: its condition is the team's own, read
  after every loop, so it never depended on a clock. Its first loop() now applies
  the mechanism, matching the timed form.

- **SDK-reference tests would fail on Windows.** `ConstantPool.listClasses`
  compared a slash-separated internal-name prefix against
  `Path.relativize(...).toString()`, which yields `\` there, so the scan found
  nothing and the suite failed. Separators are normalised now.

- **`WaitState` and `HoldState` accepted `NaN` and infinite durations.**
  `seconds < 0` does not catch `NaN`, because every comparison against it is
  false; a `NaN` duration then converted to zero nanoseconds and the wait
  silently vanished. `Double.POSITIVE_INFINITY` overflowed the deadline to a value
  in the past, so the state ended on its first iteration. Both are rejected now,
  and deadline arithmetic saturates instead of wrapping.

### Changed

- **`StateMachineOpMode` enters its route on START, not on INIT.** The route is
  still built during INIT, so Synapse's hardware map is available to
  `buildStates()`, but `machine.start()` now runs from `onSafeStart()`. A state's
  `init()` therefore runs with the OpMode live, instead of while the Driver
  Station still shows INIT and the driver may sit there indefinitely — which is
  the same hazard `HoldState` and `WaitState` were working around by arming their
  deadlines on the first `loop()`.

  **Breaking for a team that previews or tunes a route** by watching states run
  under a Driver Station Init state: nothing is entered until START is pressed. A
  team already using `onSafeInit()` to add init-phase work must move that work to
  `onRouteInited()`, or to `onSafeLoop()`, both of which are open.

  The `HoldState` / `WaitState` first-`loop()` workaround is left in place on
  purpose. Its original justification is now only half true, but a `State` cannot
  see how its machine is being driven — it is just as correct under a bare
  `StateMachine` — and simplifying it would shift when the mechanism is first
  powered by an iteration, which wants testing on a robot rather than a diff.

- **`StateMachineOpMode`'s Synapse lifecycle hooks are now `final`.**
  `onSafeInit()`, `onSafeStart()` and `onSafeStop()` were overridable, and
  overriding one without calling `super` broke the route silently: drop
  `onSafeInit()` and `machine` stays null, so the OpMode dies of an NPE on its
  first loop, after START, where a team is least able to diagnose it. This is now
  a compile error, with `onRouteInited()` / `onRouteStarted()` as the supported
  place to hook the lifecycle.

  `onSafeLoop()` stays overridable deliberately — extra per-iteration work is a
  reasonable thing to want — and its javadoc now says to call `super`.

- **`HoldState` applies its action once, on entry**, rather than on every
  `loop()`. The OpMode loop runs at several hundred iterations a second, so this
  was several hundred redundant writes per second — and, through a `SafeDevice`,
  several hundred round trips to the hardware thread — for a mechanism whose
  output does not change while the state is active. `stop()` still releases
  exactly once, so the documented `true`-then-`false` contract is unchanged.

- **`FtcStubFidelityTest` now checks every stub class, and fields as well as
  methods.** It previously covered the three classes the adapter happens to
  reference, which is checking only the part someone already looked at; the two
  stub bugs above were sitting in the classes it skipped. It also no longer
  assumes a working directory, receiving both jars as absolute system properties
  from `build.gradle`.

- **`SdkReferenceTest` verifies references against the real AAR**, not only
  against its own allowlist, and its scan is now descriptor-aware. The old scan
  filtered on the reference's *owner*, which silently dropped the one SDK field
  the adapter reads (`telemetry`) and would have dropped `requestOpModeStop()`
  too, since javac emits the qualifying type as the owner and the method's
  descriptor is `()V`. The allowlist's three `OpMode.init/loop/stop` entries were
  also exempted from the dead-entry check and could never fail; the adapter
  overrides Synapse's hooks rather than the SDK's lifecycle methods, so those
  entries have been removed.

- **`StateMachine`'s clock constructor and `WaitState`/`HoldState`'s are now
  consistently public**, so a team's tests outside the package can inject a clock
  for deterministic timings.

### Added

- `TimingTest`, covering duration validation, the `NaN` and infinity cases, and
  saturation of an overflowing deadline.


## [0.1.3] - 2026-09-30

### Fixed

- **`Telemetry.update()` returned the wrong type in the compile-time stub, which
  broke every auto on a Robot Controller.** The stub declared `void update()`
  where the SDK declares `boolean update()`. The call site compiled, the whole
  test suite passed, and the first telemetry flush threw:

  ```
  java.lang.NoSuchMethodError: No interface method update()V in class
    Lorg/firstinspires/ftc/robotcore/external/Telemetry;
      at com.aaravlabs.autonomy.ftc.StateMachineOpMode.reportTelemetry
  ```

  Every call site named the descriptor `()V` where the SDK declares `()Z`.
  Ignoring a return value is legal Java, so nothing warned; the JVM matches
  descriptors exactly, so nothing worked. **Upgrade to 0.1.3 to fix this.**
  0.1.2 and 0.1.1 cannot be repaired -- Maven Central releases are immutable --
  so if you are on either, move.

  Not an SDK version problem: `Telemetry.update()` returns `boolean` in both
  11.2.1 and 12.0.0.

### Added

- **`FtcStubFidelityTest`**, which verifies the hand-written FTC stubs against
  the real SDK instead of against a hand-written list. The build resolves
  `org.firstinspires.ftc:RobotCore` and extracts its `classes.jar`; the test
  compares the stub's declared methods to the real ones by descriptor.

  This exists because the existing `SdkReferenceTest` allowlist is a constant
  in source, and the entry typed from memory rather than transcribed from the
  AAR was the one that shipped the bug -- the test was checking that my
  assumption agreed with itself. The new test has no hand-maintained list in
  the loop.

  Verified by reintroducing `void update()`: three tests fail, and the message
  names the mismatch and the consequence.

### Changed

- `ftcSdkVersion` now names 11.2.1 in `build.gradle`, and the
  `SdkReferenceTest` allowlist is labelled to match. The stub was verified
  against both 11.2.1 and 12.0.0 earlier and covers classes that are identical
  across the two, so either works; the build now proves itself against the one
  it names.

## [0.1.2] - 2026-09-30

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
- **0.1.1, 0.1.2, and 0.1.0 all carry identical source.** They differ only in
  how they are distributed. Take the highest.

### Release history, including a mistake

0.1.0 went to GitHub Packages unsigned. 0.1.1 was the first attempt at a
signed Central release, and its first deployment was correctly rejected:
Central could not resolve the signing key, which was at that point only in a
local keyring. The key was published to `keyserver.ubuntu.com` and the release
retried.

The retry also failed, with `is currently being published in another
deployment` -- a tag push had triggered a publish at the same moment as a
manual dispatch, and two bundles for one coordinate collided. **At that point
0.1.1 was wrongly declared dead**, on the reading that the surviving
deployment was stuck: its `updateTimestamp` had not moved for several minutes
and Central refused to drop it (`can only drop deployments that are in a
VALIDATED or FAILED state`).

That reading was wrong. `PUBLISHING` on Central Portal is simply slow -- it sat
in that state for around 16 minutes and then completed. 0.1.1 is on Maven
Central, signed and intact, and 0.1.2 was cut for a problem that did not
exist. The version number was burned on a false premise.

What the collision did cost is one wasted CI run, not a release.

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
