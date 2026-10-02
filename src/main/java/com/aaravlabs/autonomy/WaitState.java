package com.aaravlabs.autonomy;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Waits for a fixed number of seconds and then moves on. Useful as a settle delay at the start of
 * a routine, or as a readable placeholder while a route is being built out.
 */
public final class WaitState extends AbstractState {

    private final String name;
    private final double seconds;
    private final LongSupplier nanoTime;

    private long endNanos;

    /** Creates a wait of {@code seconds}, reported to the Driver Station as "Wait". */
    public WaitState(double seconds) {
        this("Wait", seconds, System::nanoTime);
    }

    /**
     * Creates a wait of {@code seconds}.
     *
     * @param name what to call this wait on the Driver Station, e.g. "Settle"
     */
    public WaitState(String name, double seconds) {
        this(name, seconds, System::nanoTime);
    }

    /**
     * Creates a wait with an explicit clock, so a test outside this package can make timings
     * deterministic. The other constructors measure against {@link System#nanoTime()}.
     */
    public WaitState(String name, double seconds, LongSupplier nanoTime) {
        Timing.requireFiniteNonNegative(seconds);
        this.name = Objects.requireNonNull(name, "name");
        this.seconds = seconds;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void init() {
        endNanos = Timing.deadline(nanoTime.getAsLong(), seconds);
        setEndCondition(() -> nanoTime.getAsLong() >= endNanos);
    }

    @Override
    public void loop() {
        // Nothing to do: this state ends on time alone.
    }

    @Override
    public String name() {
        return name;
    }
}