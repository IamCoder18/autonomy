package com.aaravlabs.autonomy;

/**
 * Deadline arithmetic shared by the timed states, kept in one place because both the
 * overflow guard and the argument validation have to agree.
 *
 * <p>Deliberately not public: this is an implementation detail of {@link WaitState}
 * and {@link HoldState}, and it is package private so it does not widen the API.
 *
 * <p>Everything here works in {@code long} nanoseconds because {@code java.time} is
 * Android API 26 and the FTC SDK declares {@code minSdkVersion=24}. See
 * {@code NoAndroidApiLeakTest}.
 */
final class Timing {

    static final long NANOS_PER_SECOND = 1_000_000_000L;

    private Timing() {
    }

    /**
     * Validates a duration argument.
     *
     * <p>{@code seconds < 0} is not enough on its own, because every comparison against
     * {@code NaN} is false and so {@code NaN} sails through a negative check. A wait of
     * {@code NaN} seconds then converts to zero nanoseconds and disappears without a
     * word, which on a route is a step that silently does not happen. Infinity is
     * rejected for the same class of reason: it is never what a team means by a
     * duration, and it is better to fail at construction than to hold a mechanism for
     * the rest of the match.
     *
     * @throws IllegalArgumentException if {@code seconds} is negative, NaN, or infinite
     */
    static void requireFiniteNonNegative(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException(
                    "seconds must be a finite number >= 0, was " + seconds);
        }
    }

    /**
     * The instant, in the clock's own units, at which a duration started at
     * {@code now} has elapsed.
     *
     * <p>Saturating rather than wrapping. Adding a duration to a clock reading can
     * overflow, and an overflowed deadline wraps to a value in the past, so the state
     * would end on its very first iteration instead of waiting -- the same visible
     * outcome as the {@code NaN} case above, reached by arithmetic rather than by a
     * bad literal. Clamping at {@link Long#MAX_VALUE} keeps "a very long time" meaning
     * a very long time.
     *
     * @param now     the current reading of the clock
     * @param seconds the duration; must already have passed
     *                {@link #requireFiniteNonNegative(double)}
     * @return the deadline in the same units as {@code now}
     */
    static long deadline(long now, double seconds) {
        long delta = (long) (seconds * NANOS_PER_SECOND);
        if (delta > 0 && now > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        return now + delta;
    }
}