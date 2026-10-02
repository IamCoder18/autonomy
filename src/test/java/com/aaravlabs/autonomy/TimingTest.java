package com.aaravlabs.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Duration validation and deadline arithmetic.
 *
 * <p>A timed state is the one place where a bad number turns into a missing step on the field.
 * {@code WaitState("Settle", NaN)} used to be accepted -- every comparison against {@code NaN}
 * is false, so a negative-only guard does not catch it -- and then converted to zero nanoseconds
 * and disappeared. An auto that waits 0.75 seconds and quietly waits for nothing is the kind of
 * failure that shows up as a two-point loss and no error anywhere.
 */
class TimingTest {

    private final FakeClock clock = new FakeClock();

    @Test
    @DisplayName("rejects NaN, which a negative check alone lets through")
    void rejectsNaN() {
        // NaN < 0 is false, so `if (seconds < 0) throw` never fires for it.
        assertFalse(Double.NaN < 0, "precondition: NaN is not caught by a negative check");
        assertThrowsIllegalArgument(() -> new WaitState("Settle", Double.NaN));
        assertThrowsIllegalArgument(() -> HoldState.forSeconds("Intake", Double.NaN, on -> {}));
    }

    @Test
    @DisplayName("rejects infinity, which would otherwise overflow the deadline")
    void rejectsInfinity() {
        assertThrowsIllegalArgument(() -> new WaitState("Settle", Double.POSITIVE_INFINITY));
        assertThrowsIllegalArgument(() -> new WaitState("Settle", Double.NEGATIVE_INFINITY));
        assertThrowsIllegalArgument(
                () -> HoldState.forSeconds("Intake", Double.POSITIVE_INFINITY, on -> {}));
    }

    @Test
    @DisplayName("names the problem in the message")
    void explainsWhatIsWrong() {
        IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new WaitState("Settle", Double.NaN));

        String message = thrown.getMessage();
        assertTrue(message.contains(">= 0"), message);
        assertTrue(message.contains("NaN"), message);
    }

    @Test
    @DisplayName("still accepts zero and any ordinary duration")
    void acceptsOrdinaryDurations() {
        // Before init() a state runs on the run-once default, so endCondition() is true and
        // says nothing about the duration. Drive it through a machine instead.
        StateMachine zero = new StateMachine(new WaitState("Zero", 0));
        zero.start();
        assertFalse(zero.isFinished(), "init() alone must not end the route");
        zero.update();
        assertTrue(zero.isFinished(), "a zero wait should still cost exactly one iteration");

        StateMachine ordinary = new StateMachine(
                java.util.Arrays.asList(new WaitState("Settle", 0.75)), clock);
        ordinary.start();
        ordinary.update();
        assertFalse(ordinary.isFinished(), "0.75 s has not elapsed yet");

        assertEquals("Settle", new WaitState("Settle", 0.75).name());
        HoldState.forSeconds("Intake", 0.001, on -> { });
    }

    @Test
    @DisplayName("a huge but finite duration does not wrap into a deadline in the past")
    void saturatesInsteadOfOverflowing() {
        // Nine billion seconds is absurd, but the point is that "absurd" must not mean "already
        // elapsed". Adding it to a clock reading wraps to a negative number, and a deadline in
        // the past ends the state on its very first iteration -- the same visible outcome as
        // the NaN case above, arrived at by arithmetic rather than by a bad literal.
        double absurd = 9_300_000_000d;
        long now = 1_000_000_000L;

        long deadline = Timing.deadline(now, absurd);

        assertTrue(deadline > now, "the deadline must be in the future, was " + deadline);
        assertEquals(Long.MAX_VALUE, deadline, "a duration that cannot fit saturates at MAX");

        // And end to end, through the state that uses it.
        List<Boolean> calls = new ArrayList<>();
        StateMachine machine = new StateMachine(
                java.util.Arrays.asList(HoldState.forSeconds("Endless", absurd, calls::add)),
                clock);
        machine.start();
        for (int i = 0; i < 5; i++) {
            machine.update();
        }
        assertFalse(machine.isFinished(), "an absurd duration must not become an instant one");
        assertEquals(List.of(true), calls, "and it must not release itself either");
    }

    @Test
    @DisplayName("a duration that fits is not disturbed by the saturation guard")
    void leavesOrdinaryDeadlinesAlone() {
        long now = 1_000_000_000L;
        assertEquals(now + 750_000_000L, Timing.deadline(now, 0.75));
        assertEquals(now, Timing.deadline(now, 0));
    }

    private static void assertThrowsIllegalArgument(Runnable construction) {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, construction::run);
    }
}