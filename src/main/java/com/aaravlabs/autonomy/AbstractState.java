package com.aaravlabs.autonomy;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Base class for {@link State}s that stores the end condition for them.
 *
 * <pre>{@code
 * public class ShootState extends AbstractState {
 *     public ShootState(Shooter shooter) { ... }
 *
 *     @Override
 *     public void init() {
 *         shooter.start();
 *         setEndCondition(shooter::isShootComplete);
 *     }
 *
 *     @Override
 *     public void loop() {}
 *
 *     @Override
 *     public void stop() { shooter.stop(); }
 * }</pre>
 *
 * <p>{@code loop()} has no default; {@code init()} and {@code stop()} do.
 */
public abstract class AbstractState implements State {

    /**
     * Default used when the end condition is never set: loop once, then finish, so a state that
     * forgets to call {@link #setEndCondition} in its {@code init()} moves the routine on instead
     * of stalling it.
     */
    private static final BooleanSupplier RUN_ONCE = () -> true;

    private BooleanSupplier endCondition = RUN_ONCE;

    @Override
    public final BooleanSupplier endCondition() {
        return endCondition;
    }

    /**
     * Sets the condition that ends this state. Call this from {@link #init()}.
     *
     * @throws NullPointerException if {@code endCondition} is {@code null}
     */
    protected final void setEndCondition(BooleanSupplier endCondition) {
        this.endCondition = Objects.requireNonNull(endCondition, "endCondition");
    }

    /** Does nothing by default; override to set up hardware or timers. */
    @Override
    public void init() {
    }

    /** Does nothing by default; override to cleanup and stop motors. */
    @Override
    public void stop() {
    }
}
