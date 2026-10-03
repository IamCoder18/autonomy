package com.aaravlabs.autonomy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A {@link State} made of other states: a named phase of a route, holding the steps that make
 * it up. Groups nest to any depth, so a route can be structured instead of merely long.
 *
 * <pre>{@code
 * return Arrays.asList(
 *         new WaitState("Settle", 0.75),
 *         new Submachine("RightScissor",
 *                 new Submachine("Drive", forward(), strafe()),
 *                 new Submachine("Turn", turnAway()),
 *                 () -> seeGoal()),
 *         HoldState.until("Shoot", shooter::isShootComplete, on -> shooter.setRunning(on)));
 * }</pre>
 *
 * <p>A group ends when its last child ends, and costs no extra iterations to get there: when the
 * deepest running state finishes, the runner retires it and every group it was the last child of
 * in the same {@code update()}. Nesting four deep therefore costs exactly what nesting one deep
 * costs.
 *
 * <h2>Why this implements {@link State} instead of extending {@link AbstractState}</h2>
 *
 * <p>Both of {@code AbstractState}'s defaults are wrong for a group, and each would fail on every
 * run rather than rarely:
 *
 * <ul>
 *   <li>Its run-once fallback would end every group after a single iteration, because a group
 *       normally installs no end condition at all -- being exhausted is the runner's business,
 *       not the group's.</li>
 *   <li>{@link AbstractState#isEndConditionDefaulted()} would report every {@code Submachine} as
 *       broken, so {@code StateMachineOpMode} would print a "no end condition, ran one loop"
 *       warning on the most common state in a nested route.</li>
 * </ul>
 *
 * <p>Implementing the interface directly costs a few duplicated lines and dodges both. The runner
 * recognises groups with {@code instanceof}, the same way it already recognises
 * {@code AbstractState}, which is what keeps {@link State} itself unchanged and leaves a team that
 * implements {@code State} directly unaffected.
 *
 * <h2>Doing something on entry or exit</h2>
 *
 * <p>{@code init()}, {@code loop()} and {@code stop()} are overridable, so a group can raise a mast
 * on entry and lower it on exit:
 *
 * <pre>{@code
 * public class Raising extends Submachine {
 *     public Raising(Mast mast, State... steps) {
 *         super("Raise", steps);
 *         this.mast = mast;
 *     }
 *
 *     @Override public void init()  { mast.raise(); }
 *     @Override public void stop()  { mast.lower(); }
 * }
 * }</pre>
 *
 * <p>A group that exits early calls {@code stop()} just as one that ran out does, so the exit hook
 * cannot be left hanging in either case.
 *
 * <h2>Cycles are impossible by construction</h2>
 *
 * <p>A child has to exist before the group that holds it, and {@link #children()} is final and
 * immutable, so the containment graph cannot loop. That is why the runner needs no cycle detection
 * -- and why a subclass cannot introduce one.
 */
public class Submachine implements State {

    /**
     * The default exit condition: never leave early.
     *
     * <p>Deliberately the opposite of {@code AbstractState}'s run-once default. A group that
     * "forgets" its end condition should run all of its children and then finish, which is the
     * only safe reading of a missing condition here.
     */
    private static final BooleanSupplier NEVER = () -> false;

    private final String name;
    private final List<State> children;
    private final BooleanSupplier exitCondition;

    /** How many non-group states this group holds, directly or nested. Precomputed for telemetry. */
    private final int steps;

    /**
     * Creates a group that runs {@code children} in order.
     *
     * @throws NullPointerException     if {@code name} or {@code children} is {@code null}
     * @throws IllegalArgumentException if {@code children} is empty, or contains a {@code null}
     */
    public Submachine(String name, State... children) {
        this(name, Arrays.asList(Objects.requireNonNull(children, "children")));
    }

    /**
     * Creates a group that runs {@code children} in order.
     *
     * @throws NullPointerException     if {@code name} or {@code children} is {@code null}
     * @throws IllegalArgumentException if {@code children} is empty, or contains a {@code null}
     */
    public Submachine(String name, List<State> children) {
        this(name, children, NEVER);
    }

    /**
     * Creates a group that runs {@code children} in order, and can also be left early.
     *
     * <p>{@code exitCondition} is read once per OpMode iteration, after the group's {@code loop()}
     * and after the active child's, so a phase can end the moment something becomes true without
     * its children having to know about it. When it fires, the child that is running is stopped
     * first, so a mechanism it was holding is still released.
     *
     * <p>Note the ordering, because it is what makes leaving early safe: the state the group is
     * running is always checked before the group's own exit condition, and any state that was
     * entered is always stopped before the group itself stops. So a group whose condition is
     * already true when it is entered still runs the first state it entered to that state's own
     * end -- it does not skip its body -- and it cannot leave a mechanism running on the way out.
     * That is the same guarantee every other transition in this library makes, and it is why a
     * state that powers hardware in its {@code init()} is safe inside a group that leaves early.
     *
     * @param exitCondition ends the group early when it returns {@code true}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code children} is empty, or contains a {@code null}
     */
    public Submachine(String name, List<State> children, BooleanSupplier exitCondition) {
        this.name = Objects.requireNonNull(name, "name");
        this.exitCondition = Objects.requireNonNull(exitCondition, "exitCondition");
        Objects.requireNonNull(children, "children");

        List<State> copy = new ArrayList<>(children.size());
        int count = 0;
        for (int i = 0; i < children.size(); i++) {
            State child = children.get(i);
            if (child == null) {
                throw new IllegalArgumentException(
                        "Child " + i + " of submachine '" + name + "' is null");
            }
            copy.add(child);
            count += stepsIn(child);
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "Submachine '" + name + "' needs at least one child; a group with nothing in it is never entered");
        }
        this.children = Collections.unmodifiableList(copy);
        this.steps = count;
    }

    @Override
    public final BooleanSupplier endCondition() {
        return exitCondition;
    }

    /** Does nothing by default; override to do work when the group is entered. */
    @Override
    public void init() {
    }

    /** Does nothing by default; override to do work every iteration the group is active. */
    @Override
    public void loop() {
    }

    /** Does nothing by default; override to clean up when the group is left. */
    @Override
    public void stop() {
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * The states this group holds.
     *
     * <p>Final and package private, like {@link Timing}, so it does not widen the public API and
     * so that a subclass cannot return something mutable and make the containment graph cyclic.
     */
    final List<State> children() {
        return children;
    }

    /** How many non-group states this group holds, directly or nested. */
    final int stepCount() {
        return steps;
    }

    /**
     * How many non-group states a list holds, counting nested groups by their own totals.
     *
     * <p>Shared with {@link StateMachine}, which applies it to the top-level route.
     *
     * @param states a route that has already been checked for {@code null} entries
     * @return the number of steps, at least one
     */
    static int countSteps(List<State> states) {
        int count = 0;
        for (int i = 0; i < states.size(); i++) {
            count += stepsIn(states.get(i));
        }
        return count;
    }

    private static int stepsIn(State state) {
        return state instanceof Submachine ? ((Submachine) state).steps : 1;
    }
}