# Autonomy

[![Test](https://github.com/IamCoder18/autonomy/actions/workflows/test.yml/badge.svg)](https://github.com/IamCoder18/autonomy/actions/workflows/test.yml)
[![Publish](https://github.com/IamCoder18/autonomy/actions/workflows/publish.yml/badge.svg)](https://github.com/IamCoder18/autonomy/actions/workflows/publish.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Latest release](https://img.shields.io/github/v/tag/IamCoder18/autonomy?label=release)](https://github.com/IamCoder18/autonomy/releases)
[![Maven Package](https://img.shields.io/badge/Maven-GitHub%20Packages-blue)](https://github.com/IamCoder18/autonomy/packages)

A small state-machine framework for **FIRST** Tech Challenge autonomous
routines.

An autonomous is a fixed sequence of steps: settle, drive, intake, shoot.
Autonomy runs that sequence one OpMode iteration at a time, and each step is a
`State` that decides for itself when it is done. Reordering a route is a
one-line change, and nothing else about the auto moves.

> ~10 KB JAR. One runtime dependency (Synapse). Desktop-JVM testable.

```java
@Autonomous(name = "Auto: Example", group = "Auto")
public class ExampleAuto extends StateMachineOpMode {

    @Override
    protected List<State> buildStates() {
        SafeDevice<DcMotorEx> intake = safeMap.device(DcMotorEx.class, "intake");

        return Arrays.asList(
                new WaitState("Settle", 0.75),
                HoldState.forSeconds("Intake on", 1.0,
                        on -> intake.run(m -> m.setPower(on ? 1.0 : 0.0))),
                new WaitState("Pause", 0.5),
                HoldState.forSeconds("Intake again", 1.0,
                        on -> intake.run(m -> m.setPower(on ? 1.0 : 0.0))));
    }
}
```

That is the whole OpMode. Running the sequence, releasing the intake when the
routine is interrupted, ending the OpMode once the route is done, and printing
progress to the Driver Station all happen in the base class.

## Why Autonomy?

FTC autonomous code has a specific problem: it is a fixed script, but it is
written as one enormous `runOpMode()` method. That works until the routine
changes, and then a `sleep(1500)` in the wrong place costs a match. The usual
answers are worse:

- **Timed `Thread.sleep`.** Easy to write, impossible to tune. It blocks the
  loop, so a state that needs to watch a sensor cannot.
- **Pedro Pathing and similar.** Powerful, and a large dependency to take on
  for a routine that is four steps long. Worth it at 18-ball; overkill at four.
- **Hand-rolled `switch` on a step counter.** Works, and every team ends up
  maintaining its own version of it, usually with the same two bugs: a
  transition that forgets to release the previous mechanism, and a state that
  can be skipped entirely if its end condition is already true when it starts.

Autonomy is the small version of that last one, with those two bugs designed
out and the whole thing under test.

- **Every state loops at least once.** The end condition is checked *after*
  `loop()`, never before, so a state whose condition is already true still gets
  one iteration. No skipped steps.
- **`stop()` is mandatory in spirit.** Releasing a mechanism is the state's own
  job, so a `HoldState` physically cannot leave a motor spinning when the routine
  moves on.
- **Interruption is handled.** The Robot Controller can stop an OpMode at any
  moment; the active state's `stop()` runs on the way out.
- **The framework is plain Java.** No Android, no FTC SDK, no Synapse. The
  behaviour is unit tested on a desktop JVM, and so are your states.

## The one idea

A `State` is four methods and one condition:

```java
public interface State {
    void init();                            // once on entry
    void loop();                            // once per OpMode iteration
    void stop();                            // once on exit
    BooleanSupplier endCondition();         // read after every loop()
}
```

`StateMachine` enforces the order and nothing else. You do not register states,
subscribe to topics, or declare transitions; a route is a `List<State>` in
order.

Most states should extend `AbstractState`, which stores the end condition for
you and gives `init()` and `stop()` empty defaults:

```java
public final class ShootState extends AbstractState {

    private final Shooter shooter;

    public ShootState(Shooter shooter) {
        this.shooter = shooter;
    }

    @Override
    public void init() {
        shooter.start();
        setEndCondition(shooter::isShootComplete);
    }

    @Override
    public void loop() {
        // The condition is doing the work.
    }

    @Override
    public void stop() {
        shooter.stop();
    }
}
```

Note the constructor. **States take their dependencies as arguments** rather
than reaching for a hardware map. That is what keeps them testable off the
robot, and it is why the framework itself has no robot imports at all — a rule
enforced by `PackageBoundaryTest`.

## Built-in states

Two cover most routes:

| State | Ends when | Use for |
| --- | --- | --- |
| `WaitState` | a fixed number of seconds has passed | settle delays, readable gaps in a route |
| `HoldState.forSeconds(name, secs, action)` | the time is up | run a mechanism for a known duration |
| `HoldState.until(name, condition, action)` | the condition is true | run until a sensor, colour, or limit says stop |

`HoldState`'s action is a `Consumer<Boolean>`, not a `Runnable`, and that is the
point: it receives `true` while active and `false` on the way out, so releasing
the mechanism is part of the state and cannot be forgotten.

It is applied once, on the first `loop()` iteration, rather than on every one —
the OpMode loop runs at several hundred iterations a second, and re-writing the
same output that often is pure overhead. The clock starts there too, not at
`init()`. `init()` runs while the Driver Station still shows INIT, and the driver
can sit there for as long as they like before pressing START. Anchoring a hold
in `init()` means the intake is already spinning before the match is live, and
that a 0.2 s shoot can expire entirely while waiting for START.

```java
HoldState.forSeconds("Run intake", 1.0, on -> intake.run(m -> m.setPower(on ? 1.0 : 0.0)));
HoldState.until("Shoot", shooter::isShootComplete, on -> shooter.setRunning(on));
```

## Installation

Autonomy is published to **Maven Central**, which is the path you want. It
needs no authentication, and the standard `FtcRobotController` template already
declares `mavenCentral()`, so adding the dependency is all it takes:

```gradle
dependencies {
    implementation 'com.aaravlabs:autonomy:0.1.3'
}
```

Autonomy also has a **GitHub Packages** mirror at
`maven.pkg.github.com/IamCoder18/autonomy`. Note that GitHub requires a personal
access token with the `read:packages` scope to pull a package *even when the
repository is public* — that is a GitHub restriction, not an Autonomy one. If in
doubt, use Maven Central above.

To use the mirror instead, add the repository to `build.dependencies.gradle`:

```gradle
repositories {
    mavenCentral()
    google()
    maven {
        url = uri("https://maven.pkg.github.com/IamCoder18/autonomy")
        credentials {
            username = findProperty("githubUser") ?: System.getenv("GITHUB_USER") ?: System.getenv("GITHUB_ACTOR")
            password = findProperty("githubToken") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'com.aaravlabs:autonomy:0.1.3'
}
```

and configure credentials in `~/.gradle/gradle.properties` (never commit it):

```properties
githubUser=<your-github-username>
githubToken=<a-token-with-read:packages>
```

Autonomy depends on `com.aaravlabs:synapse:0.4.0`, which is on Maven Central
and needs no authentication. If you already depend on Synapse, Gradle resolves
the highest version and the transitive declaration costs you nothing.

### Also needs Synapse

`StateMachineOpMode` extends Synapse's `SafeOpMode`, because that is what routes
hardware access onto the hardware thread. If you would rather not adopt
Synapse, you can skip this artifact entirely and drive a `StateMachine`
yourself from a plain `OpMode` — the framework has no Synapse dependency of its
own, and only the `ftc` subpackage does.

## API reference

| Type | Where | Purpose |
| --- | --- | --- |
| `State` | `com.aaravlabs.autonomy` | One step of a routine: `init`/`loop`/`stop` + an end condition. |
| `AbstractState` | `com.aaravlabs.autonomy` | Base class that stores the end condition. |
| `StateMachine` | `com.aaravlabs.autonomy` | Runs a fixed list of states, one OpMode iteration at a time. |
| `WaitState` | `com.aaravlabs.autonomy` | Waits N seconds. |
| `HoldState` | `com.aaravlabs.autonomy` | Holds a mechanism for a time or until a condition, then releases it. |
| `StateMachineOpMode` | `com.aaravlabs.autonomy.ftc` | `OpMode` base class. Subclass it and return a route from `buildStates()`. |

### `StateMachine`

| Method | Notes |
| --- | --- |
| `start()` | Enters the first state. Call once, from `init()`. |
| `update()` | Advances one OpMode iteration. Call once per `loop()`. |
| `stop()` | Ends early, running the active state's `stop()`. |
| `currentState()` / `currentIndex()` | What is running, for telemetry. |
| `elapsedSeconds()` / `currentStateElapsedSeconds()` | Timing, for tuning a route. |
| `isFinished()` | True once the route is exhausted. `StateMachineOpMode` reads this to end the OpMode. |

A machine **cannot be replayed** — build a new one per run. Its states carry
whatever they accumulated last time, and reusing them is the one way to get an
auto that behaves differently on the second match.

## Testing your own states

Because states take their dependencies through constructors and the framework
has no robot dependencies, a team's states can be tested like any other class.
This is the main reason for the constructor rule, and the reason a custom state
gets its devices passed in rather than looked up:

```java
@Test
void releasesTheIntakeWhenTheRouteIsAborted() {
    List<Boolean> calls = new ArrayList<>();
    StateMachine machine = new StateMachine(
            HoldState.forSeconds("Intake", 30, calls::add));

    machine.start();
    machine.update();
    machine.stop();

    assertEquals(List.of(true, false), calls);
}
```

No robot, no SDK, no Synapse. If a state needs something untestable, that is a
signal to push the hardware access behind an interface you can fake.

## Testing & development

65 JUnit 5 tests covering the runner's ordering guarantees, timing, abort
behaviour, misuse, argument validation, the Android API-level floor, the
package boundary, the link to Synapse, and the exact SDK members the compiled
adapter references.

```bash
./gradlew build                     # compile and test
./gradlew test                      # tests only
./gradlew publishToMavenLocal       # install into ~/.m2 for experimentation
```

Requirements: JDK 11+, Gradle 9.x (the wrapper is checked in).

Several of the tests are guards rather than behaviour tests, and they are worth
knowing about because they are the reason some seemingly harmless changes will
be rejected:

- **`NoAndroidApiLeakTest`** — the FTC SDK declares `minSdkVersion=24`, but
  `java.time` and `java.nio.file` are API 26. Using one throws
  `NoClassDefFoundError` on an older Robot Controller, and no desktop test would
  catch it. Scans the compiled bytecode.
- **`PackageBoundaryTest`** — nothing in `com.aaravlabs.autonomy` may reference
  the FTC SDK, Android, or Synapse. This is what keeps the framework testable
  off the robot.
- **`StateMachineOpModeLinkageTest`** — asserts the adapter still lines up with
  the Synapse release it compiles against, so a Synapse rename fails on a laptop
  rather than as an `AbstractMethodError` on a competition day.
- **`FtcStubFidelityTest`** — the important one, and the reason the previous two
  releases shipped broken jars. Autonomy compiles its adapter against
  hand-written SDK stubs, because the real SDK ships only as AARs, and a stub
  that is more permissive than the real SDK produces a jar that compiles, passes
  every other test, and throws `NoSuchMethodError` on a robot. 0.1.2 shipped a
  stub declaring `void update()` where the SDK declares `boolean update()`.
  So this one resolves the real `RobotCore` AAR and compares the stub against it
  by descriptor, every class and every member, with no hand-written list in the
  loop. It earned its place twice more: it caught `@Autonomous`'s stub declaring
  `preselectTeleOp()` as `int` where the SDK declares `String`, and
  `HardwareMap.get(String)` stubbed as returning `Object` where the SDK returns
  `HardwareDevice`.
- **`SdkReferenceTest`** — the companion to the above. Where that one asks "is
  the stub faithful?", this asks "what does the adapter actually call?", and
  checks each reference resolves in the real AAR rather than only against a list
  of expected members. Both directions matter: a dead allowlist entry is a
  permission nobody checks, which is why there is no exemption list.
- **`StateMachineOpModeEndsItselfTest`** — that a finished route actually ends
  the OpMode. The SDK drives an OpMode as `while (!stopRequested) { loop(); }`,
  so a routine that runs out of states without asking to stop keeps looping until
  the Robot Controller force-kills it at the match timer: hardware already
  released, Driver Station still showing RUNNING.

## Contributing

Issues and PRs welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md) for the
workflow, [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) for the code of conduct,
and [CHANGELOG.md](./CHANGELOG.md) for the version history.

## License

[MIT](./LICENSE)

## Credits

Extracted from the autonomous framework developed by
[ATAARobotics](https://github.com/ATAARobotics) Team 23684, and built to sit
alongside [Synapse](https://github.com/IamCoder18/synapse). Created and
maintained by [IamCoder18](https://github.com/IamCoder18).
