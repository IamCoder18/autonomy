package com.aaravlabs.autonomy;

import java.util.function.BooleanSupplier;

/**
 * One step of an autonomous routine, shaped like an OpMode: {@link #init()} once on entry, then
 * {@link #loop()} until {@link #endCondition()} returns true, then {@link #stop()}.
 *
 * <p>{@link StateMachine} enforces that order and checks the end condition only after {@link #loop()},
 * so every state loops at least once.
 *
 * <p>Prefer extending {@link AbstractState}, which supplies the end-condition plumbing. States take
 * their dependencies through their constructor, which is what keeps them free of subsystem and
 * Android imports.
 */
public interface State {

    /** Runs once on entry, before the first {@link #loop()}. Set the end condition here. */
    void init();

    /** Runs once per OpMode iteration while this state is active, and at least once in total. */
    void loop();

    /**
     * Runs once after the end condition was met, before the next state is initialised. Release
     * anything this state was holding: zero motor power, park a servo, stop a timer.
     */
    void stop();

    /**
     * The condition that ends this state. A plain {@link BooleanSupplier} so it can close over
     * whatever the state measures. Read after every {@link #loop()}, so it must be cheap and must
     * never block.
     */
    BooleanSupplier endCondition();

    /** Label shown on the Driver Station. Defaults to the simple class name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
