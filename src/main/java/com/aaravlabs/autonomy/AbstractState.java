package com.aaravlabs.autonomy;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Base class for {@link State}s that stores the end condition.
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
 *
 * <p><strong>Call {@code setEndCondition} from {@code init()}, never from
 * {@code loop()}.</strong> The condition is read after every {@code loop()}, so a
 * state that sets it during its first iteration still sees the run-once default
 * on that read and finishes immediately. Nothing inside the state can notice that,
 * which is what {@link #isEndConditionDefaulted()} is for.
 */
public abstract class AbstractState implements State {

    /**
     * Default used when the end condition is never set: loop once, then finish, so a state that
     * forgets to call {@link #setEndCondition} in its {@code init()} moves the routine on instead
     * of stalling it.
     */
    private static final BooleanSupplier RUN_ONCE = () -> true;

    private BooleanSupplier endCondition = RUN_ONCE;
    private boolean endConditionSet = false;

    @Override
    public final BooleanSupplier endCondition() {
        return endCondition;
    }

    /**
     * Sets the condition that ends this state. Call this from {@link #init()}.
     *
     * <p>Not from {@link #loop()}: the first read of the condition happens immediately
     * after the first {@code loop()}, so a condition installed there is seen one
     * iteration too late and the state ends after a single loop. That failure is
     * quiet, and on a real route it is indistinguishable from a state that was
     * skipped.
     *
     * @throws NullPointerException if {@code endCondition} is {@code null}
     */
    protected final void setEndCondition(BooleanSupplier endCondition) {
        this.endCondition = Objects.requireNonNull(endCondition, "endCondition");
        this.endConditionSet = true;
    }

    /**
     * Whether this state is still running on the run-once default, meaning
     * {@code init()} never called {@link #setEndCondition}.
     *
     * <p>Such a state loops exactly once and the route moves on. That is deliberate --
     * an auto that stalls scores nothing, and one that skips a step might still score
     * something -- but it is otherwise invisible, so this is what a caller can check
     * in order to warn about it. {@code StateMachineOpMode} surfaces it as a
     * {@code State problem} telemetry line.
     *
     * @return {@code true} while the end condition has never been set
     */
    public final boolean isEndConditionDefaulted() {
        return !endConditionSet;
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