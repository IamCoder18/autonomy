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
 * <p><strong>Call {@code setEndCondition} from {@code init()}.</strong> It is read after
 * every {@code loop()}, so setting it during the first {@code loop()} does work -- but
 * setting it later cannot, because the run-once default has already ended the state by
 * then. A state that installs its condition on, say, only its second loop runs exactly
 * once and is skipped, which is the failure this class's fallback is designed to keep
 * quiet. Nothing inside the state can notice, which is what
 * {@link #isEndConditionDefaulted()} is for.
 *
 * <p>See {@link State} for the ordering the runner guarantees.
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
     * <p>Prefer {@link #init()}. Setting it during the first {@link #loop()} does take
     * effect, since the condition is read at the end of that same iteration -- but a
     * condition installed any later cannot: the run-once default has already ended the
     * state by then, so the state runs exactly once and is skipped. That failure is quiet,
     * and on a real route it is indistinguishable from a state that was forgotten.
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