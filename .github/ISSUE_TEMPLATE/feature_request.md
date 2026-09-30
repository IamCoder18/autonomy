---
name: Feature request
about: Suggest a new state or API
title: ''
labels: enhancement
assignees: ''
---

## Problem

What pain point does this solve?

## Proposed API

Sketch the type or method you'd like to see.

```java
public final class DriveState extends AbstractState {
    public DriveState(Drive drive, Pose target) { ... }
}
```

## Where it would live

`com.aaravlabs.autonomy` if it is plain Java and testable on a desktop JVM, or
`com.aaravlabs.autonomy.ftc` if it needs Synapse or the SDK. Please say which,
and why.

## Alternatives considered

What else did you look at? A `HoldState` with a custom condition, or a state
written by hand in your own OpMode, often covers a lot. If you tried that first,
say what was awkward about it.

## Additional context

Anything else — an example auto that would use it, links to similar ideas in
other teams' codebases, etc.
