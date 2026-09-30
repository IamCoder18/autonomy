package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitStateTest {

    @Test
    @DisplayName("keeps the auto in the wait until the clock passes the requested duration")
    void waitsForTheRequestedDuration() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(Arrays.asList(new WaitState("Settle", 0.5, clock)), clock);

        machine.start();
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
        StateMachine machine = new StateMachine(Arrays.asList(new WaitState("Settle", 0.5, clock)), clock);

        machine.start();
        clock.advance(10);
        machine.update();

        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("a zero second wait costs exactly one OpMode iteration")
    void zeroSecondWaitEndsImmediately() {
        StateMachine machine = new StateMachine(new WaitState("Nothing", 0));

        machine.start();
        assertFalse(machine.isFinished());

        machine.update();
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("shows its given name on the Driver Station")
    void reportsItsName() {
        assertEquals("Settle", new WaitState("Settle", 1).name());
        assertEquals("Wait", new WaitState(1).name(), "an unnamed wait should still label itself usefully");
    }

    @Test
    @DisplayName("rejects a negative duration")
    void rejectsNegativeDuration() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new WaitState("Bad", -0.1));

        assertTrue(thrown.getMessage().contains(">= 0"), thrown.getMessage());
    }
}
