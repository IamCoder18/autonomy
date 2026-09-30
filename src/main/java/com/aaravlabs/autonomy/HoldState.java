package com.aaravlabs.autonomy;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Holds a mechanism at some output for a fixed time or until a condition is met, then releases
 * it.
 *
 * <p>The action is a {@link Consumer} of {@code boolean} rather than a {@code Runnable} so that
 * releasing the mechanism belongs to the state: it receives {@code true} while active and {@code
 * false} once the state ends, so it can never leave a motor spinning after the routine moves on.
 *
 * <pre>{@code
 * HoldState.forSeconds("Run intake", 1.0, on -> intake.run(m -> m.setPower(on ? 1.0 : 0.0)));
 * HoldState.until("Shoot", shooter::isShootComplete, on -> shooter.setRunning(on));
 * }</pre>
 */
public final class HoldState extends AbstractState {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final String name;
    private final Consumer<Boolean> action;
    private final Double seconds;
    private final BooleanSupplier condition;
    private final LongSupplier nanoTime;

    private long endNanos;

    private HoldState(String name, Consumer<Boolean> action, Double seconds,
            BooleanSupplier condition, LongSupplier nanoTime) {
        boolean bothSet = seconds != null && condition != null;
        boolean neitherSet = seconds == null && condition == null;
        if (bothSet || neitherSet) {
            throw new IllegalArgumentException(
                    "HoldState needs exactly one of seconds or condition");
        }
        if (seconds != null && seconds < 0) {
            throw new IllegalArgumentException("seconds must be >= 0, was " + seconds);
        }
        this.name = Objects.requireNonNull(name, "name");
        this.action = Objects.requireNonNull(action, "action");
        this.seconds = seconds;
        this.condition = condition;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Holds for {@code seconds}, then releases.
     *
     * @param name label for this hold: shown as "State" on the Driver Station telemetry while it
     *     runs, and used to name it in error messages; e.g. "Run intake"
     */
    public static HoldState forSeconds(String name, double seconds, Consumer<Boolean> action) {
        return new HoldState(name, action, seconds, null, System::nanoTime);
    }

    /**
     * Holds until {@code condition} returns {@code true}, then releases.
     *
     * @param name label for this hold: shown as "State" on the Driver Station telemetry while it
     *     runs, and used to name it in error messages; e.g. "Shoot"
     */
    public static HoldState until(String name, BooleanSupplier condition,
            Consumer<Boolean> action) {
        return new HoldState(name, action, null,
                Objects.requireNonNull(condition, "condition"), System::nanoTime);
    }

    /** As {@link #forSeconds}, with an explicit clock so tests are deterministic. Package private. */
    static HoldState forSeconds(String name, double seconds, Consumer<Boolean> action,
            LongSupplier nanoTime) {
        return new HoldState(name, action, seconds, null, nanoTime);
    }

    @Override
    public void init() {
        if (condition != null) {
            setEndCondition(condition);
        } else {
            endNanos = nanoTime.getAsLong() + (long) (seconds * NANOS_PER_SECOND);
            setEndCondition(() -> nanoTime.getAsLong() >= endNanos);
        }
    }

    @Override
    public void loop() {
        action.accept(Boolean.TRUE);
    }

    @Override
    public void stop() {
        action.accept(Boolean.FALSE);
    }

    @Override
    public String name() {
        return name;
    }
}
