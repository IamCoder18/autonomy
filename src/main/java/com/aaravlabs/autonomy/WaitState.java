package com.aaravlabs.autonomy;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Waits for a fixed duration and then moves on. Useful as a settle delay at the start of a
 * routine, or as a readable placeholder while a route is being built out.
 *
 * <p>The duration can be given with a {@link TimeUnit}, from {@link TimeUnit#NANOSECONDS} through
 * {@link TimeUnit#DAYS}. A duration given without a unit is measured in seconds, so existing
 * routes keep their meaning.
 *
 * <p>The clock is nanosecond based, but the state is checked only once per OpMode iteration, so a
 * wait shorter than an iteration cannot end sooner than the next check: nanosecond and
 * microsecond waits are approximate, and their accuracy is not guaranteed at low values.
 */
public final class WaitState extends AbstractState {

    private final String name;
    private final long durationNanos;
    private final LongSupplier nanoTime;

    private long endNanos;

    /**
     * False until the first {@link #loop()}, which is when the deadline is armed.
     *
     * <p>Arming here rather than in {@code init()} costs at most one OpMode iteration and buys one
     * thing: a {@code State} cannot know when START was pressed, because it is driven by a bare
     * {@code StateMachine} just as often as by {@code StateMachineOpMode}. Anchoring the deadline
     * to something the OpMode is actually running on keeps this state correct either way. (For the
     * state-owned {@link Timer} the two are the same moment for the first state of a route, and
     * for a later state both fall on the iteration that enters it.)
     */
    private boolean running = false;

    /**
     * Creates a wait of {@code seconds}, reported to the Driver Station as "Wait".
     *
     * @param seconds how long to wait; must be >= 0
     */
    public WaitState(double seconds) {
        this("Wait", seconds, System::nanoTime);
    }

    /**
     * Creates a wait of {@code seconds}.
     *
     * @param name what to call this wait on the Driver Station, e.g. "Settle"
     * @param seconds how long to wait; must be >= 0
     */
    public WaitState(String name, double seconds) {
        this(name, seconds, System::nanoTime);
    }

    /**
     * Creates a wait of {@code seconds} with an explicit clock, so a test outside this package can
     * make timings deterministic. The other second-based constructors measure against
     * {@link System#nanoTime()}.
     */
    public WaitState(String name, double seconds, LongSupplier nanoTime) {
        this(name, nanosFromSeconds(seconds), nanoTime);
    }

    /**
     * Creates a wait of {@code duration}, reported to the Driver Station as "Wait".
     *
     * @param duration how long to wait; must be >= 0
     * @param unit the unit of {@code duration}, e.g. {@link TimeUnit#MILLISECONDS}
     */
    public WaitState(double duration, TimeUnit unit) {
        this("Wait", duration, unit, System::nanoTime);
    }

    /**
     * Creates a wait of {@code duration}.
     *
     * @param name what to call this wait on the Driver Station, e.g. "Settle"
     * @param duration how long to wait; must be >= 0
     * @param unit the unit of {@code duration}, e.g. {@link TimeUnit#MILLISECONDS}
     */
    public WaitState(String name, double duration, TimeUnit unit) {
        this(name, duration, unit, System::nanoTime);
    }

    /**
     * Creates a wait with an explicit clock, so a test outside this package can make timings
     * deterministic. The other constructors measure against {@link System#nanoTime()}.
     */
    public WaitState(String name, double duration, TimeUnit unit, LongSupplier nanoTime) {
        this(name, nanosFrom(duration, unit), nanoTime);
    }

    private WaitState(String name, long durationNanos, LongSupplier nanoTime) {
        this.name = Objects.requireNonNull(name, "name");
        this.durationNanos = durationNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    private static long nanosFromSeconds(double seconds) {
        return nanosFrom(seconds, TimeUnit.SECONDS);
    }

    private static long nanosFrom(double duration, TimeUnit unit) {
        Timing.requireFiniteNonNegative(duration);
        return (long) (duration * Objects.requireNonNull(unit, "unit").toNanos(1));
    }

    @Override
    public void init() {
        running = false;
        setEndCondition(() -> running && nanoTime.getAsLong() >= endNanos);
    }

    @Override
    public void loop() {
        // Nothing to do beyond arming the deadline: this state ends on time alone.
        if (!running) {
            running = true;
            endNanos = Timing.deadline(nanoTime.getAsLong(), durationNanos);
        }
    }

    @Override
    public String name() {
        return name;
    }
}
