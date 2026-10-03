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
 * time.
 *
 * <p>Per {@link #update()} it runs the active state's {@code loop()}, then checks that state's end
 * condition. When the condition is met it calls {@code stop()} and, if there is another state,
 * {@code init()} on it. The new state gets its first {@code loop()} on the next {@code update()},
 * so every state loops at least once and each transition costs one iteration.
 *
 * <p>Not thread safe. Drive it from the OpMode loop only.
 */
public final class StateMachine {

    private final List<State> states;
    private final LongSupplier nanoTime;

    private int index = -1;
    private State current = null;
    private boolean started = false;
    private boolean finished = false;
    private long startNanos = 0L;
    private long stateStartNanos = 0L;

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
        enter(0);
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

        State state = current;
        state.loop();
        if (!isDone(state)) {
            return;
        }

        state.stop();
        if (index + 1 < states.size()) {
            enter(index + 1);
        } else {
            finished = true;
            current = null;
            index = -1;
        }
    }

    /**
     * Ends the routine early, running the active state's {@code stop()} so it releases its
     * hardware.
     *
     * <p>Call this from the OpMode's own {@code stop()}. Safe to call more than once, and safe to
     * call on a machine that never started or already finished.
     */
    public void stop() {
        if (!started || finished) {
            return;
        }
        finished = true;
        if (current != null) {
            current.stop();
            current = null;
        }
        index = -1;
    }

    /** How many states are in this route. */
    public int size() {
        return states.size();
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
     */
    public State currentState() {
        return current;
    }

    /** The index of {@link #currentState()}, or {@code -1} when no state is active. */
    public int currentIndex() {
        return index;
    }

    /** Seconds since the {@link #start()}, or {@code 0} before it. */
    public double elapsedSeconds() {
        return started ? toSeconds(nanoTime.getAsLong() - startNanos) : 0.0;
    }

    /** Seconds spent in {@link #currentState()}, or {@code 0} when no state is active. */
    public double currentStateElapsedSeconds() {
        return current == null ? 0.0 : toSeconds(nanoTime.getAsLong() - stateStartNanos);
    }

    private void enter(int next) {
        index = next;
        current = states.get(next);
        stateStartNanos = nanoTime.getAsLong();
        current.init();
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
