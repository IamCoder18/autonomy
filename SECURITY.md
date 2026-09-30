# Security policy

## Supported versions

| Version | Supported          |
|---------|--------------------|
| 0.1.x   | Yes                |
| < 0.1   | No                 |

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Instead, email
security reports to the maintainers via the contact listed in
[the repository](https://github.com/IamCoder18/autonomy).

We will acknowledge receipt within 48 hours and aim to ship a fix or mitigation
within 7 days for anything that affects a routine's safety or determinism.

## Scope

Autonomy runs on a competition robot, where the cost of a bug is a failed match
rather than a breach, so the bar is "would this let a routine do something the
team did not ask for". Issues we care about:

- **A mechanism left running.** A state that can skip its `stop()`, or an
  `update()` that can advance past a state whose end condition was never read,
  can leave a motor spinning after the auto ends. This is the single most
  important property in the library.
- **A state being skipped or double-run.** The ordering guarantees in
  `StateMachine` are the contract; anything that breaks them is a bug even if
  it looks like a timing improvement.
- **Unchecked misuse turning into silent wrong behaviour.** `update()` before
  `start()`, replaying a machine, or an empty route should fail loudly, not
  produce a plausible-looking routine that does the wrong thing.
- **Build-chain issues** — dependency confusion, signing key compromise, or a
  published artifact that does not match its sources.

Out of scope (please open a regular issue):

- Performance regressions on user code.
- Documentation errors.
- Anything requiring a malicious device or a modified Robot Controller.

## Note on the FTC stub

`ftc-stub/` contains hand-written stand-ins for a handful of FTC SDK classes,
used only at compile time and never shipped. They are not a security surface,
but a signature that drifts from the real SDK would produce a
`NoSuchMethodError` at runtime. `StateMachineOpModeLinkageTest` exists to catch
the more likely version of that problem — a Synapse API change — and should be
run whenever the Synapse dependency is bumped.
