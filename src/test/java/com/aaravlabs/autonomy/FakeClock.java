package com.aaravlabs.autonomy;

import java.util.function.LongSupplier;

/** A hand-advanced clock, so timing assertions do not depend on the speed of the test machine. */
final class FakeClock implements LongSupplier {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private long nanos;

    @Override
    public long getAsLong() {
        return nanos;
    }

    /** Moves the clock forward and returns itself, so calls can be chained. */
    FakeClock advance(double seconds) {
        nanos += (long) (seconds * NANOS_PER_SECOND);
        return this;
    }
}
