/**
 * The robot-facing half of Autonomy: everything that needs a real FTC OpMode.
 *
 * <p>{@link com.aaravlabs.autonomy.ftc.StateMachineOpMode} is the only class in the library that
 * touches Synapse, the FTC SDK, or Android. It is a thin adapter: it starts a {@link
 * com.aaravlabs.autonomy.StateMachine}, advances it once per OpMode loop, forwards interruption
 * to the active state, and prints progress to the Driver Station.
 *
 * <p>Keeping the coupling to this single class is what lets the state framework itself be
 * compiled and unit-tested on a desktop JVM. If you are tempted to add an import from
 * {@code com.qualcomm.robotcore} to something in {@code com.aaravlabs.autonomy}, put the class in
 * this package instead.
 */
package com.aaravlabs.autonomy.ftc;
