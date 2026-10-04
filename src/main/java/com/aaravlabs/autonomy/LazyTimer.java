package com.aaravlabs.autonomy;

import java.util.function.LongSupplier;

/**
 * The timer a state carries, created on first use.
 *
 * <p>{@link AbstractState} and {@link Submachine} both give a state one timer, and this is what
 * makes that free for the states that never ask for it. A route holds dozens of states, most of
 * which are steps that finish on their own condition and have no reason to be holding a stopwatch;
 * building a {@link Timer} for each of them would be an allocation per state per run for nothing,
 * and one more object to keep alive for the length of the match.
 *
 * <p>The clock and name are passed in per call rather than held here, because the holder itself is
 * created in the state's constructor -- before a subclass has necessarily decided what to call
 * itself -- and building a {@link Timer} from an undecided name would be a worse bug than not
 * having built one yet.
 *
 * <p>Not thread safe, matching the runner and the {@link Timer} it hands out.
 */
final class LazyTimer {

    private Timer timer = null;

    /**
     * This state's timer, built against {@code nanoTime} and named {@code name} on first call.
     *
     * <p>The name is read when the timer is built rather than when it is first started, so a state
     * that names itself consistently reports the same name however many times it is restarted.
     */
    Timer get(LongSupplier nanoTime, String name) {
        if (timer == null) {
            timer = new Timer(name, nanoTime);
        }
        return timer;
    }

    /** Builds the timer if needed and restarts it with no timeout. */
    void start(LongSupplier nanoTime, String name) {
        get(nanoTime, name).start();
    }

    /**
     * Builds the timer if needed and restarts it with a timeout.
     *
     * @throws IllegalArgumentException if {@code seconds} is negative, NaN, or infinite
     */
    void start(LongSupplier nanoTime, String name, double seconds) {
        get(nanoTime, name).start(seconds);
    }

    /** Stops the timer, or does nothing if this state never asked for one. */
    void stop() {
        if (timer != null) {
            timer.stop();
        }
    }
}