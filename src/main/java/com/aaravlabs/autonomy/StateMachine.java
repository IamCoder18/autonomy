package com.aaravlabs.autonomy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Runs a fixed sequence of {@link State}s, one after another, driven one OpMode iteration at a
 * time. States may be grouped into {@link Submachine}s, nested to any depth.
 *
 * <p>Per {@link #update()} it runs the active state's {@code loop()}, then checks that state's end
 * condition. When the condition is met it calls {@code stop()} and, if there is another state,
 * {@code init()} on it. The new state gets its first {@code loop()} on the next {@code update()},
 * so every state loops at least once.
 *
 * <p>A transition costs one iteration no matter how deeply the state that just finished was
 * nested: when it was the last child still running, every group it belonged to is retired in the
 * same {@code update()}, in one loop over the stack rather than a level of recursion per level of
 * nesting.
 *
 * <p>Not thread safe. Drive it from the OpMode loop only.
 */
public final class StateMachine {

    private final List<State> states;
    private final LongSupplier nanoTime;
    private final int stepCount;

    /**
     * One entry per active level of nesting. The bottom frame is the route itself and has no
     * owner; each frame above it is a {@link Submachine} that is currently running.
     */
    private final List<Frame> stack = new ArrayList<>();

    private boolean started = false;
    private boolean finished = false;
    private long startNanos = 0L;
    private long stateStartNanos = 0L;

    /**
     * One level of the route being run.
     *
     * <p>{@link #leafOffsets} is what lets {@link #currentStepIndex()} answer "which step of the
     * whole auto is this" in constant time when children are themselves groups: the entry for
     * child {@code i} is the position of that child's first step in the route's flat ordering.
     * Filled once when the frame is pushed, so telemetry costs nothing per iteration.
     */
    private static final class Frame {

        /** The group being run, or {@code null} for the route itself. */
        final Submachine owner;
        final List<State> children;
        final int[] leafOffsets;

        /** The child about to be entered, or running. Always less than {@code children.size()}. */
        int index;

        Frame(Submachine owner, List<State> children, int stepBase) {
            this.owner = owner;
            this.children = children;
            this.leafOffsets = new int[children.size()];
            int next = stepBase;
            for (int i = 0; i < children.size(); i++) {
                leafOffsets[i] = next;
                State child = children.get(i);
                next += child instanceof Submachine ? ((Submachine) child).stepCount() : 1;
            }
        }
    }

    /**
     * Creates a machine that will run {@code states} in the order given.
     *
     * @throws NullPointerException if {@code states} or any state in it is {@code null}
     * @throws IllegalArgumentException if {@code states} is empty
     */
    public StateMachine(State... states) {
        this(Arrays.asList(Objects.requireNonNull(states, "states")));
    }

    /**
     * Creates a machine that will run {@code states} in the order given.
     *
     * @throws NullPointerException if {@code states} or any state in it is {@code null}
     * @throws IllegalArgumentException if {@code states} is empty
     */
    public StateMachine(List<State> states) {
        this(states, System::nanoTime);
    }

    /**
     * Creates a machine with an explicit clock, so tests get deterministic timings. Use the other
     * constructors unless you are testing.
     */
    public StateMachine(List<State> states, LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.states = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(states, "states")));
        if (this.states.isEmpty()) {
            throw new IllegalArgumentException(
                    "A StateMachine needs at least one state; the route is empty");
        }
        for (int i = 0; i < this.states.size(); i++) {
            if (this.states.get(i) == null) {
                throw new IllegalArgumentException("State " + i + " of the route is null");
            }
        }
        this.stepCount = Submachine.countSteps(this.states);
    }

    /**
     * Enters the first state. Call once, from init.
     *
     * <p>Build a new machine for every run; one cannot be replayed, because its states carry
     * whatever they accumulated during the previous run.
     *
     * @throws IllegalStateException if this machine has already been started
     */
    public void start() {
        if (started) {
            throw new IllegalStateException(
                    "This StateMachine was already started; build a new one for each run");
        }
        started = true;
        startNanos = nanoTime.getAsLong();
        stack.add(new Frame(null, states, 0));
        enter(stack.size() - 1);
    }

    /**
     * Advances the routine by one OpMode iteration. Call once per loop, until
     * {@link #isFinished()}.
     *
     * <p>Does nothing once the routine has finished or been {@link #stop() stopped}.
     *
     * @throws IllegalStateException if called before {@link #start()}, or if a state was left
     *     without an end condition
     */
    public void update() {
        if (!started) {
            throw new IllegalStateException("update() was called before start()");
        }
        if (finished) {
            return;
        }

        tick();
    }

    /**
     * Ends the routine early, running the active state's {@code stop()} so it releases its
     * hardware.
     *
     * <p>With nesting, every group that was entered also has its {@code stop()} run, innermost
     * first: a group that raised a mast on entry has to lower it on the way out. States that had
     * already been retired are left alone, because they were stopped on their way out.
     *
     * <p>Call this from the OpMode's own {@code stop()}. Safe to call more than once, and safe to
     * call on a machine that never started or already finished.
     */
    public void stop() {
        if (!started || finished) {
            return;
        }
        // Read the active state before marking the machine finished, because currentState()
        // reports nothing once it has.
        State active = currentState();
        finished = true;
        active.stop();
        for (int level = stack.size() - 1; level >= 1; level--) {
            stack.remove(level).owner.stop();
        }
        stack.clear();
    }

    /** How many states are in this route, counting a {@link Submachine} as one. */
    public int size() {
        return states.size();
    }

    /**
     * How many steps this route runs, counting every non-group state however deeply nested.
     *
     * <p>Equal to {@link #size()} for a route with no groups in it.
     */
    public int stepCount() {
        return stepCount;
    }

    /** Whether {@link #start()} has been called. */
    public boolean isStarted() {
        return started;
    }

    /** Whether the routine has run to completion or been {@link #stop() stopped}. */
    public boolean isFinished() {
        return finished;
    }

    /**
     * The state being run right now, or {@code null} before {@link #start()} and after the routine
     * ends.
     *
     * <p>Always a non-group state: when the active state is a {@link Submachine}, the runner is
     * already inside it and this returns the step it is running.
     */
    public State currentState() {
        if (!started || finished) {
            return null;
        }
        Frame top = stack.get(stack.size() - 1);
        return top.children.get(top.index);
    }

    /** The index of {@link #currentState()} within the route, or {@code -1} when none is active. */
    public int currentIndex() {
        if (!started || finished) {
            return -1;
        }
        return stack.get(0).index;
    }

    /**
     * Which step of the whole route is running, counting every non-group state in order however
     * deeply nested, or {@code -1} when none is active.
     *
     * <p>The flat-route counterpart of {@link #currentIndex()}, and the number to show when what
     * matters is how far through the auto the robot is rather than which top-level phase it is in.
     */
    public int currentStepIndex() {
        if (!started || finished) {
            return -1;
        }
        Frame top = stack.get(stack.size() - 1);
        return top.leafOffsets[top.index];
    }

    /**
     * How many groups enclose {@link #currentState()}, plus one: {@code 1} for a flat route.
     *
     * @return {@code 0} when no state is active
     */
    public int depth() {
        return started && !finished ? stack.size() : 0;
    }

    /**
     * The states leading to {@link #currentState()}, outermost group first and the running state
     * last. A flat route's path is just that one state.
     *
     * <p>For telemetry, so a Driver Station line can say which phase a step belongs to.
     *
     * @return an unmodifiable list, empty when no state is active
     */
    public List<State> path() {
        if (!started || finished) {
            return Collections.emptyList();
        }
        List<State> path = new ArrayList<>(stack.size());
        for (int level = 1; level < stack.size(); level++) {
            path.add(stack.get(level).owner);
        }
        path.add(currentState());
        return Collections.unmodifiableList(path);
    }

    /** Seconds since the {@link #start()}, or {@code 0} before it. */
    public double elapsedSeconds() {
        return started ? toSeconds(nanoTime.getAsLong() - startNanos) : 0.0;
    }

    /** Seconds spent in {@link #currentState()}, or {@code 0} when no state is active. */
    public double currentStateElapsedSeconds() {
        return currentState() == null ? 0.0 : toSeconds(nanoTime.getAsLong() - stateStartNanos);
    }

    /**
     * One OpMode iteration: every active level does its per-iteration work, then the deepest
     * running state is checked and, if it or an enclosing group is finished, retired.
     */
    private void tick() {
        // Outermost first, so a group sees the iteration begin before the state it is driving.
        for (int level = 1; level < stack.size(); level++) {
            stack.get(level).owner.loop();
        }

        State leaf = currentState();
        leaf.loop();

        if (isDone(leaf)) {
            leaf.stop();
            advance();
            return;
        }

        int exiting = deepestExitingGroup();
        if (exiting >= 0) {
            leaveGroup(exiting);
        }
    }

    /**
     * The innermost group whose exit condition has fired, or {@code -1} if none has.
     *
     * <p>Innermost first so that a group nested inside another that is also leaving gets to stop
     * its own work before its parent does.
     */
    private int deepestExitingGroup() {
        for (int level = stack.size() - 1; level >= 1; level--) {
            if (isDone(stack.get(level).owner)) {
                return level;
            }
        }
        return -1;
    }

    /**
     * Leaves the group at {@code level} early: stops the state running inside it and every group
     * below it, innermost first, then moves the enclosing group on.
     *
     * <p>The enclosing frame's index still points at the group being left, which is what lets
     * {@link #advance()} take the same path it takes when a child simply ran out.
     */
    private void leaveGroup(int level) {
        currentState().stop();
        for (int l = stack.size() - 1; l >= level; l--) {
            stack.remove(l).owner.stop();
        }
        advance();
    }

    /**
     * Moves on from the state that just finished, retiring every group whose children are now all
     * done and entering the next one.
     *
     * <p>A loop rather than a level of recursion per level of nesting, so a transition costs the
     * same one iteration however deeply the finished state was nested.
     */
    private void advance() {
        while (true) {
            Frame top = stack.get(stack.size() - 1);
            top.index++;

            if (top.index < top.children.size()) {
                enter(stack.size() - 1);
                return;
            }

            Submachine owner = top.owner;
            stack.remove(stack.size() - 1);
            if (stack.isEmpty()) {
                finished = true;
                return;
            }
            // This level is done. Its owner is a group that has run out of children, so it stops
            // too; the parent above it just advanced and is tested by the next turn of this loop.
            if (owner != null) {
                owner.stop();
            }
        }
    }

    /**
     * Enters the child of {@code parentLevel} that its frame points at, initialising every level
     * down to the first state that is not itself a {@link Submachine}.
     *
     * <p>Descending in a loop rather than one level per call is what keeps entering a tree of
     * groups a single transition, and it upholds the rule that a state is never initialised
     * without a matching {@code stop()}: by the time this returns, the deepest frame always
     * points at a state that has been initialised, so every path that retires the machine has
     * something real to stop.
     *
     * <p>The group's first child is initialised here and gets its first {@code loop()} on the
     * next {@code update()}, which is the same one-iteration-per-transition rule that applies to
     * any other state.
     */
    private void enter(int parentLevel) {
        int level = parentLevel;
        while (true) {
            Frame parent = stack.get(level);
            State next = parent.children.get(parent.index);
            next.init();
            if (!(next instanceof Submachine)) {
                stateStartNanos = nanoTime.getAsLong();
                return;
            }
            Submachine group = (Submachine) next;
            stack.add(new Frame(group, group.children(), parent.leafOffsets[parent.index]));
            level = stack.size() - 1;
        }
    }

    private boolean isDone(State state) {
        BooleanSupplier condition = state.endCondition();
        if (condition == null) {
            throw new IllegalStateException("State '" + state.name()
                    + "' has no end condition; call setEndCondition(...) in its init()");
        }
        return condition.getAsBoolean();
    }

    private static double toSeconds(long nanos) {
        return nanos / (double) Timing.NANOS_PER_SECOND;
    }
}
