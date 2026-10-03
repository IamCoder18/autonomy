package com.aaravlabs.autonomy;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A {@link Submachine} test double that appends every lifecycle callback to a shared log, the
 * same way {@link RecordingState} does for states.
 */
final class RecordingSubmachine extends Submachine {

    /** The default exit condition, matching the framework's: never leave early. */
    private static final BooleanSupplier NEVER = () -> false;

    private final String id;
    private final List<String> log;

    RecordingSubmachine(String id, List<String> log, State... children) {
        this(id, log, null, Arrays.asList(children));
    }

    RecordingSubmachine(String id, List<String> log, BooleanSupplier exitCondition, List<State> children) {
        super(id, children, exitCondition == null ? NEVER : exitCondition);
        this.id = id;
        this.log = log;
    }

    @Override
    public void init() {
        log.add(id + ".init");
    }

    @Override
    public void loop() {
        log.add(id + ".loop");
    }

    @Override
    public void stop() {
        log.add(id + ".stop");
    }
}