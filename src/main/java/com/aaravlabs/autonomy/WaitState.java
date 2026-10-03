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

    /**
     * False until the first {@link #loop()}, which is when the deadline is armed.
     *
     * <p>{@code init()} runs while the Driver Station shows INIT and the driver may sit there
     * for an unbounded time before pressing START. A settle delay anchored there is measured
     * against a period in which the OpMode is not running, so a 0.75 s settle can be entirely
     * consumed before the robot moves -- which defeats the point of a settle delay.
     */
    private boolean running = false;

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
        running = false;
        setEndCondition(() -> running && nanoTime.getAsLong() >= endNanos);
    }

    @Override
    public void loop() {
        // Nothing to do beyond arming the deadline: this state ends on time alone. The wait is
        // measured from here rather than from init(), so time spent waiting for the driver to
        // press START does not eat into it.
        if (!running) {
            running = true;
            endNanos = Timing.deadline(nanoTime.getAsLong(), seconds);
        }
    }

    @Override
    public String name() {
        return name;
    }
}