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
        return advanceNanos((long) (seconds * NANOS_PER_SECOND));
    }

    /** Moves the clock forward by an exact number of nanoseconds, for sub-second waits. */
    FakeClock advanceNanos(long deltaNanos) {
        nanos += deltaNanos;
        return this;
    }
}
