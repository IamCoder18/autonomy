---
name: Bug report
about: Something doesn't work
title: ''
labels: bug
assignees: ''
---

## What happened

A clear, concise description of the bug.

## How to reproduce

A minimal auto that triggers the issue. The route is usually enough — the states
themselves rarely matter.

```java
@Autonomous(name = "Repro", group = "Auto")
public class Repro extends StateMachineOpMode {
    @Override
    protected List<State> buildStates() {
        return Arrays.asList(
                new WaitState("Settle", 0.75),
                HoldState.forSeconds("Intake on", 1.0, on -> { ... }));
    }
}
```

## Versions

- Autonomy: `com.aaravlabs:autonomy:X.Y.Z`
- Synapse: `com.aaravlabs:synapse:X.Y.Z`
- FTC SDK (RobotCore version): `org.firstinspires.ftc:RobotCore:X.Y.Z`
- Android Gradle Plugin:
- Gradle:

## Expected vs actual

**Expected:** ...
**Actual:** ...

## On the Driver Station

Paste the telemetry lines if the auto got far enough to print any. `Auto`,
`State`, `State time`, and `Auto time` narrow this down fast.

```
paste here
```
