package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Timer} on its own.
 *
 * <p>The wiring -- that a state's timer starts when the runner starts rather than when the route is
 * built -- is in {@code TimerInAStateTest}, and a group's own use of one is in
 * {@code SubmachineTest}. This class is about the arithmetic and the contracts, because a timer that
 * is subtly wrong about time is the worst kind of wrong: it still compiles, still runs, and just
 * quietly ends states a fraction of a second early or late.
 */
class TimerTest {

    private static final double TOLERANCE = 1e-9;

    @Nested
    @DisplayName("before it is started")
    class BeforeStarting {

        @Test
        @DisplayName("reports no elapsed time, so a timer never counts from the moment it was built")
        void reportsNothingBeforeStarting() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            clock.advance(30);                     // driver sits in INIT for half a minute

            assertFalse(timer.isRunning());
            assertEquals(0.0, timer.elapsedSeconds(), TOLERANCE);
            assertEquals(0L, timer.elapsedNanos());
            assertFalse(timer.hasElapsed(), "a timer that was never started cannot have run out");
        }

        @Test
        @DisplayName("reports no time left, rather than a number that means nothing")
        void reportsNoTimeLeftBeforeStarting() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            assertEquals(0.0, timer.remainingSeconds(), TOLERANCE);
        }
    }

    @Nested
    @DisplayName("as a stopwatch")
    class AsAStopwatch {

        @Test
        @DisplayName("counts up from the moment it is started")
        void countsUpFromTheStart() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Drive", clock);

            timer.start();
            assertEquals(0.0, timer.elapsedSeconds(), TOLERANCE);

            clock.advance(1.5);
            assertEquals(1.5, timer.elapsedSeconds(), TOLERANCE);

            clock.advance(0.25);
            assertEquals(1.75, timer.elapsedSeconds(), TOLERANCE);
        }

        @Test
        @DisplayName("never expires on its own, because it was given no duration")
        void neverExpiresWithoutADuration() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Drive", clock);

            timer.start();
            clock.advance(600);

            assertFalse(timer.hasElapsed(), "a stopwatch has nothing to be out-lived by");
            assertEquals(600.0, timer.elapsedSeconds(), TOLERANCE);
        }

        @Test
        @DisplayName("keeps counting after it has been read, because it reads the clock every time")
        void keepsCountingAfterBeingRead() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Drive", clock);

            timer.start();
            clock.advance(2);
            timer.elapsedSeconds();

            clock.advance(3);
            assertEquals(5.0, timer.elapsedSeconds(), TOLERANCE,
                    "elapsed time is a question about now, not a value captured at start()");
        }
    }

    @Nested
    @DisplayName("as a timeout")
    class AsATimeout {

        @Test
        @DisplayName("expires exactly when the duration it was given is up")
        void expiresAtItsDuration() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Shoot", clock);

            timer.start(0.2);

            clock.advance(0.19);
            assertFalse(timer.hasElapsed(), "0.19 s of a 0.2 s timeout");

            clock.advance(0.01);
            assertTrue(timer.hasElapsed(), "the full 0.2 s is up");
        }

        @Test
        @DisplayName("holds the one duration it was given, so the target and the check cannot differ")
        void holdsTheDurationItWasGiven() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Shoot", clock);

            timer.start(0.2);
            assertEquals(0.2, timer.targetSeconds(), TOLERANCE);
        }

        @Test
        @DisplayName("a zero second timeout is already up, which costs the state one iteration")
        void zeroSecondTimeoutIsImmediatelyUp() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Instant", clock);

            timer.start(0);
            assertTrue(timer.hasElapsed());
        }

        @Test
        @DisplayName("reports how much of the timeout is left, and never less than nothing")
        void reportsTimeLeft() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start(3.0);
            assertEquals(3.0, timer.remainingSeconds(), TOLERANCE);

            clock.advance(1.0);
            assertEquals(2.0, timer.remainingSeconds(), TOLERANCE);

            clock.advance(10.0);
            assertEquals(0.0, timer.remainingSeconds(), TOLERANCE,
                    "an expired timer should print 0.0 rather than a negative countdown");
        }

        @Test
        @DisplayName("stays expired once it is, however much longer the state runs")
        void staysExpired() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Shoot", clock);

            timer.start(0.2);
            clock.advance(0.2);
            assertTrue(timer.hasElapsed());

            clock.advance(60);
            assertTrue(timer.hasElapsed());
            assertEquals(0.0, timer.remainingSeconds(), TOLERANCE);
        }
    }

    @Nested
    @DisplayName("when restarted")
    class WhenRestarted {

        @Test
        @DisplayName("starts over from zero, so a state entered twice does not inherit the first run")
        void startsOverFromZero() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Retry", clock);

            timer.start(1.0);
            clock.advance(5.0);
            assertTrue(timer.hasElapsed());

            timer.start(1.0);

            assertEquals(0.0, timer.elapsedSeconds(), TOLERANCE);
            assertFalse(timer.hasElapsed());
            assertEquals(1.0, timer.remainingSeconds(), TOLERANCE);
        }

        @Test
        @DisplayName("as a stopwatch, drops any timeout the previous run had")
        void stopwatchRestartDropsTheTimeout() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Retry", clock);

            timer.start(1.0);
            timer.start();

            assertEquals(-1.0, timer.targetSeconds(), TOLERANCE,
                    "a timer restarted with no duration should not still be expiring");
            clock.advance(1000);
            assertFalse(timer.hasElapsed());
        }
    }

    @Nested
    @DisplayName("when stopped")
    class WhenStopped {

        @Test
        @DisplayName("discards the time it had run, so it cannot go on reporting a stale elapsed time")
        void discardsItsElapsedTime() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start();
            clock.advance(4.0);
            timer.stop();

            clock.advance(10);
            assertEquals(0.0, timer.elapsedSeconds(), TOLERANCE);
            assertFalse(timer.isRunning());
            assertEquals(0L, timer.elapsedNanos());
        }

        @Test
        @DisplayName("disarms its timeout, so a stopped state cannot end on a deadline it has passed")
        void disarmsItsTimeout() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start(0.2);
            clock.advance(10);
            timer.stop();

            assertFalse(timer.hasElapsed(),
                    "a stopped timer has nothing left to expire; otherwise stopping it would still "
                            + "end the state it belonged to");
        }

        @Test
        @DisplayName("can be started again afterwards")
        void canBeStartedAgain() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start(1.0);
            timer.stop();
            timer.start(1.0);

            assertTrue(timer.isRunning());
            assertFalse(timer.hasElapsed());
        }
    }

    @Nested
    @DisplayName("checked against a number given only at the check")
    class AgainstAOneOffCheck {

        @Test
        @DisplayName("reports whether that many seconds have passed since the timer started")
        void checksAOneOffDuration() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Settle", clock);

            timer.start();
            clock.advance(0.5);

            assertFalse(timer.hasElapsed(0.75));
            assertTrue(timer.hasElapsed(0.25));
        }

        @Test
        @DisplayName("is false for a timer that is not running, whatever it is asked")
        void isFalseWhileStopped() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Settle", clock);

            clock.advance(100);

            assertFalse(timer.hasElapsed(0.0),
                    "a timer that was never started has run for no time at all, not for all of it");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a duration that is negative, NaN, or infinite, at both entry points")
        void rejectsUnusableDurations() {
            Timer timer = new Timer("Bad");

            assertThrows(IllegalArgumentException.class, () -> timer.start(-0.1));
            assertThrows(IllegalArgumentException.class, () -> timer.start(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> timer.start(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class, () -> timer.hasElapsed(-1));
            assertThrows(IllegalArgumentException.class, () -> timer.hasElapsed(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> timer.hasElapsed(Double.POSITIVE_INFINITY));
        }

        @Test
        @DisplayName("explains what was wrong with the duration")
        void explainsWhatWasWrong() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new Timer("Bad").start(Double.NaN));

            assertTrue(thrown.getMessage().contains("NaN"), thrown.getMessage());
        }

        @Test
        @DisplayName("rejects a null name or clock, rather than failing later on every read")
        void rejectsMissingArguments() {
            assertThrows(NullPointerException.class, () -> new Timer(null));
            assertThrows(NullPointerException.class, () -> new Timer("T", null));
        }
    }

    @Nested
    @DisplayName("under an awkward clock")
    class UnderAnAwkwardClock {

        @Test
        @DisplayName("keeps counting correctly across the point where the clock wraps around")
        void survivesWraparound() {
            // System.nanoTime() is specified to wrap, and a real Robot Controller runs for long
            // enough that it eventually does. Elapsed time is therefore computed as a difference of
            // two readings rather than by comparing a reading against a precomputed deadline, which
            // is the only shape that stays correct across the wrap.
            long[] now = {Long.MAX_VALUE - 500_000_000L};
            LongSupplier wrappingClock = () -> now[0];
            Timer timer = new Timer("Long run", wrappingClock);

            timer.start(1.0);

            now[0] = Long.MAX_VALUE - 400_000_000L;
            assertEquals(0.1, timer.elapsedSeconds(), 0.01);
            assertFalse(timer.hasElapsed());

            now[0] += 1_500_000_000L;              // now past Long.MAX_VALUE, so it has wrapped
            assertTrue(timer.hasElapsed(), "1.6 s is past a 1.0 s timeout, wrap or no wrap");
            assertEquals(1.6, timer.elapsedSeconds(), 0.01);
        }

        @Test
        @DisplayName("treats an absurdly long timeout as one that simply will not come up")
        void saturatesAnAbsurdTimeout() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Forever", clock);

            timer.start(Double.MAX_VALUE);

            assertFalse(timer.hasElapsed(),
                    "a duration too large for a long must not wrap into the past and expire at once");
        }
    }

    @Nested
    @DisplayName("when printed")
    class WhenPrinted {

        @Test
        @DisplayName("reads as a name and a duration, so a log line says what it is measuring")
        void readsAsANameAndDuration() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start();
            clock.advance(1.25);

            assertEquals("Climb: 1.25 s", timer.toString());
            assertEquals("Climb", timer.name());
        }

        @Test
        @DisplayName("includes the timeout, so the line does not need cross-referencing another one")
        void includesTheTimeout() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start(3.0);
            clock.advance(1.25);

            assertEquals("Climb: 1.25 s of 3.00 s", timer.toString());
        }

        @Test
        @DisplayName("reports a stopped timer as zero rather than as its last reading")
        void reportsAStoppedTimerAsZero() {
            FakeClock clock = new FakeClock();
            Timer timer = new Timer("Climb", clock);

            timer.start();
            clock.advance(9.0);
            timer.stop();

            assertEquals("Climb: 0.00 s", timer.toString());
        }
    }
}