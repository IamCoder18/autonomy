---
name: Pull Request
about: Contribute a change to Autonomy
title: ''
labels: ''
assignees: ''
---

## What

A short summary of the change.

## Why

Link the issue or describe the motivation.

## How

Anything reviewers should look at closely. In particular:

- **Does the state framework stay plain Java?** `PackageBoundaryTest` enforces
  this, but a reviewer should ask whether the new class belongs in
  `com.aaravlabs.autonomy` or `com.aaravlabs.autonomy.ftc`.
- **Does it stay inside Android API 24?** The FTC SDK declares `minSdkVersion=24`,
  so no `java.time` or `java.nio.file`.
- **Does it change the published API?** A change to `State` or `AbstractState` is
  source-breaking for every team that wrote a custom state.

## Test

What tests did you run? What's the new coverage?

```
./gradlew build
```

## Checklist

- [ ] `./gradlew build` passes locally
- [ ] New behaviour has a JUnit test
- [ ] `com.aaravlabs.autonomy` still imports nothing from `com.qualcomm`,
      `android`, or `com.aaravlabs.synapse`
- [ ] `CHANGELOG.md` updated (if user-facing)
- [ ] No new runtime dependencies
