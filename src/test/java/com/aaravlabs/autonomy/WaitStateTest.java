package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitStateTest {

    @Test
    @DisplayName("keeps the auto in the wait until the clock passes the requested duration")
    void waitsForTheRequestedDuration() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(new WaitState("Settle", 500, TimeUnit.MILLISECONDS, clock)), clock);

        machine.start();
        machine.update();                          // arms the deadline
        assertFalse(machine.isFinished(), "a fresh 0.5 s wait should not be over yet");

        clock.advance(0.25);
        machine.update();
        assertFalse(machine.isFinished(), "should still be waiting before the deadline");

        clock.advance(0.25);
        machine.update();
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("still gets one loop, even when the duration has already elapsed")
    void loopsAtLeastOnce() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(new WaitState("Settle", 500, TimeUnit.MILLISECONDS, clock)), clock);

        machine.start();
        machine.update();
        clock.advance(10);
        machine.update();

        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("time spent waiting for the driver to press START does not eat the wait")
    void doesNotConsumeTheWaitDuringInit() {
        // init() runs while the Driver Station shows INIT, and the driver may sit there for an
        // unbounded time before pressing START. A settle delay anchored in init() is measured
        // against a period in which the OpMode is not running, so a 0.75 s settle can be
        // entirely consumed before the robot moves -- which defeats the point of a settle.
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(new WaitState("Settle", 750, TimeUnit.MILLISECONDS, clock)), clock);

        machine.start();
        clock.advance(30);                         // driver takes half a minute to press START
        assertFalse(machine.isFinished());

        machine.update();
        assertFalse(machine.isFinished(), "the 0.75 s settle should begin when the OpMode runs");

        clock.advance(0.5);
        machine.update();
        assertFalse(machine.isFinished(), "0.5 s of the 0.75 s has elapsed");

        clock.advance(0.3);
        machine.update();
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("a zero duration wait costs exactly one OpMode iteration")
    void zeroDurationWaitEndsImmediately() {
        StateMachine machine = new StateMachine(new WaitState("Nothing", 0, TimeUnit.MILLISECONDS));

        machine.start();
        assertFalse(machine.isFinished());

        machine.update();
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("honours every TimeUnit from nanoseconds through days")
    void honoursEveryTimeUnit() {
        for (TimeUnit unit : new TimeUnit[] {
                TimeUnit.NANOSECONDS,
                TimeUnit.MICROSECONDS,
                TimeUnit.MILLISECONDS,
                TimeUnit.SECONDS,
                TimeUnit.MINUTES,
                TimeUnit.HOURS,
                TimeUnit.DAYS}) {
            assertWaits(1, unit);
        }
    }

    @Test
    @DisplayName("a duration too large for nanoseconds does not wrap into the past")
    void saturatesHugeDuration() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(
                        new WaitState("Endless", Long.MAX_VALUE, TimeUnit.DAYS, clock)),
                clock);

        machine.start();
        machine.update();

        assertFalse(machine.isFinished(), "an absurd duration must not become an instant one");
    }

    @Test
    @DisplayName("defaults to seconds when no TimeUnit is given")
    void defaultsToSeconds() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(new WaitState("Settle", 0.75, clock)), clock);

        machine.start();
        machine.update();
        clock.advance(0.74);
        machine.update();
        assertFalse(machine.isFinished(), "0.74 s of the 0.75 s has elapsed");

        clock.advance(0.01);
        machine.update();
        assertTrue(machine.isFinished(), "the no-unit constructor should still mean seconds");
    }

    @Test
    @DisplayName("accepts fractional durations with a TimeUnit")
    void acceptsFractionalDurationsWithAUnit() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(new WaitState("Settle", 0.75, TimeUnit.SECONDS, clock)), clock);

        machine.start();
        machine.update();
        clock.advance(0.74);
        machine.update();
        assertFalse(machine.isFinished(), "0.74 s of the 0.75 s has elapsed");

        clock.advance(0.01);
        machine.update();
        assertTrue(machine.isFinished(), "0.75 TimeUnit.SECONDS should mean 0.75 s");
    }

    @Test
    @DisplayName("shows its given name on the Driver Station")
    void reportsItsName() {
        assertEquals("Settle", new WaitState("Settle", 1, TimeUnit.SECONDS).name());
        assertEquals("Wait", new WaitState(1, TimeUnit.SECONDS).name(),
                "an unnamed wait should still label itself usefully");
        assertEquals("Settle", new WaitState("Settle", 1).name(),
                "the seconds constructor should still be available");
        assertEquals("Wait", new WaitState(1).name(),
                "an unnamed seconds wait should still label itself usefully");
    }

    @Test
    @DisplayName("rejects a negative duration in either form")
    void rejectsNegativeDuration() {
        IllegalArgumentException fromSeconds = assertThrows(
                IllegalArgumentException.class,
                () -> new WaitState("Bad", -0.1));
        assertTrue(fromSeconds.getMessage().contains(">= 0"), fromSeconds.getMessage());

        IllegalArgumentException fromUnit = assertThrows(
                IllegalArgumentException.class,
                () -> new WaitState("Bad", -100, TimeUnit.MILLISECONDS));
        assertTrue(fromUnit.getMessage().contains(">= 0"), fromUnit.getMessage());
    }

    @Test
    @DisplayName("requires a TimeUnit when one is given explicitly")
    void requiresATimeUnit() {
        assertThrows(NullPointerException.class,
                () -> new WaitState("Bad", 1, (TimeUnit) null));
    }

    private static void assertWaits(long duration, TimeUnit unit) {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(new WaitState("Wait", duration, unit, clock)), clock);

        machine.start();
        machine.update();
        assertFalse(machine.isFinished(),
                "a " + duration + " " + unit + " wait should not end before its deadline");

        clock.advanceNanos(unit.toNanos(duration));
        machine.update();
        assertTrue(machine.isFinished(),
                "a " + duration + " " + unit + " wait should end at its deadline");
    }
}
