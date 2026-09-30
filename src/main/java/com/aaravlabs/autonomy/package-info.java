/**
 * A small state-machine framework for FTC autonomous routines.
 *
 * <p>An autonomous is a fixed sequence of steps: settle, drive, intake, shoot. {@link
 * com.aaravlabs.autonomy.StateMachine} runs that sequence one OpMode iteration at a time, and each
 * step is a {@link com.aaravlabs.autonomy.State} that decides for itself when it is done.
 *
 * <p>The whole of this package is plain Java — no Android, no FTC SDK, no Synapse. That is what
 * makes the behaviour testable on a desktop JVM, and it is why states take their dependencies
 * through their constructors instead of reaching for a global hardware map. The one class that
 * does need the robot lives in {@link com.aaravlabs.autonomy.ftc}.
 *
 * <p>Entry point for a team is {@link com.aaravlabs.autonomy.ftc.StateMachineOpMode}: extend it,
 * return a route from {@code buildStates()}, and nothing else changes when the route does.
 */
package com.aaravlabs.autonomy;
