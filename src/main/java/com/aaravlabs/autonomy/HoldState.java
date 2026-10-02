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
 * releasing the mechanism belongs to the state: it receives {@code true} once on entry and
 * {@code false} once on the way out, so it can never leave a motor spinning after the routine
 * moves on.
 *
 * <p>Applied <em>once</em>, on entry, rather than on every {@code loop()}. The OpMode loop can
 * run at several hundred iterations a second, so re-applying would mean several hundred redundant
 * writes a second -- and, through a {@code SafeDevice}, several hundred round trips to the
 * hardware thread -- for a mechanism whose state does not change while the state is active.
 *
 * <pre>{@code
 * HoldState.forSeconds("Run intake", 1.0, on -> intake.run(m -> m.setPower(on ? 1.0 : 0.0)));
 * HoldState.until("Shoot", shooter::isShootComplete, on -> shooter.setRunning(on));
 * }</pre>
 */
public final class HoldState extends AbstractState {

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
        if (seconds != null) {
            Timing.requireFiniteNonNegative(seconds);
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

    /**
     * As {@link #forSeconds}, with an explicit clock, so a test outside this package can make
     * timings deterministic. The other factories measure against {@link System#nanoTime()}.
     */
    public static HoldState forSeconds(String name, double seconds, Consumer<Boolean> action,
            LongSupplier nanoTime) {
        return new HoldState(name, action, seconds, null, nanoTime);
    }

    /**
     * As {@link #until}, with an explicit clock, so a test outside this package can make timings
     * deterministic.
     */
    public static HoldState until(String name, BooleanSupplier condition,
            Consumer<Boolean> action, LongSupplier nanoTime) {
        return new HoldState(name, action, null,
                Objects.requireNonNull(condition, "condition"), nanoTime);
    }

    @Override
    public void init() {
        if (condition != null) {
            setEndCondition(condition);
        } else {
            endNanos = Timing.deadline(nanoTime.getAsLong(), seconds);
            setEndCondition(() -> nanoTime.getAsLong() >= endNanos);
        }
        action.accept(Boolean.TRUE);
    }

    @Override
    public void loop() {
        // The mechanism was applied on entry and is released by stop(); nothing to do here.
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