package com.aaravlabs.autonomy;

import java.util.List;
import java.util.function.BooleanSupplier;

/** A {@link State} test double that appends every lifecycle callback to a shared log. */
final class RecordingState extends AbstractState {

    private final String id;
    private final List<String> log;
    private final BooleanSupplier endCondition;
    private final Integer loopCount;

    private int loops;

    /** Ends on its first loop. */
    RecordingState(String id, List<String> log) {
        this(id, log, null, 1);
    }

    /** Ends on its first loop, using a caller-supplied condition. */
    RecordingState(String id, List<String> log, BooleanSupplier endCondition) {
        this(id, log, endCondition, null);
    }

    /** Runs exactly {@code loopCount} loops, then ends. */
    static RecordingState lasting(String id, List<String> log, int loopCount) {
        return new RecordingState(id, log, null, loopCount);
    }

    private RecordingState(String id, List<String> log, BooleanSupplier endCondition, Integer loopCount) {
        this.id = id;
        this.log = log;
        this.endCondition = endCondition;
        this.loopCount = loopCount;
    }

    @Override
    public void init() {
        log.add(id + ".init");
        if (endCondition != null) {
            setEndCondition(endCondition);
        } else {
            setEndCondition(() -> loops >= loopCount);
        }
    }

    @Override
    public void loop() {
        log.add(id + ".loop");
        loops++;
    }

    @Override
    public void stop() {
        log.add(id + ".stop");
    }

    @Override
    public String name() {
        return id;
    }

    int loops() {
        return loops;
    }
}
