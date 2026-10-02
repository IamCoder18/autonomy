package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldStateTest {

    private final List<String> events = new ArrayList<>();

    @Test
    @DisplayName("applies the mechanism once on entry, and releases exactly once on the way out")
    void holdsThenReleases() {
        FakeClock clock = new FakeClock();
        HoldState hold = HoldState.forSeconds("Intake on", 0.5, on -> events.add(on ? "hold" : "release"), clock);

        StateMachine machine = new StateMachine(Arrays.asList(hold), clock);
        machine.start();

        clock.advance(0.125);
        machine.update();
        clock.advance(0.125);
        machine.update();
        assertEquals(Arrays.asList("hold"), events, "the mechanism is applied on entry, not per loop");
        assertFalse(machine.isFinished());

        clock.advance(0.25);
        machine.update();
        assertEquals(Arrays.asList("hold", "release"), events);
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("does not re-apply on every OpMode iteration")
    void appliesTheMechanismOnceNotEveryLoop() {
        // The OpMode loop runs at several hundred iterations a second. Re-applying the
        // action each time meant several hundred redundant writes per second, and through a
        // SafeDevice that is several hundred round trips to the hardware thread, for a
        // mechanism whose output does not change while the state is active.
        FakeClock clock = new FakeClock();
        AtomicInteger applications = new AtomicInteger();
        HoldState hold = HoldState.forSeconds("Intake on", 10,
                on -> applications.incrementAndGet(), clock);

        StateMachine machine = new StateMachine(Arrays.asList(hold), clock);
        machine.start();

        for (int i = 0; i < 200; i++) {
            machine.update();
        }

        assertEquals(1, applications.get(),
                "200 loop iterations must not mean 200 writes to the mechanism");
        assertFalse(machine.isFinished(), "the hold is still within its 10 second window");

        machine.stop();
        assertEquals(2, applications.get(), "stopping releases, so two calls in total");
    }

    @Test
    @DisplayName("until() ends on the condition and then releases")
    void holdsUntilTheConditionIsMet() {
        AtomicInteger readings = new AtomicInteger();
        HoldState hold = HoldState.until(
                "Shoot", () -> readings.incrementAndGet() >= 3, on -> events.add(on ? "hold" : "release"));

        StateMachine machine = new StateMachine(hold);
        machine.start();
        machine.update();
        machine.update();
        assertFalse(machine.isFinished());

        machine.update();
        assertTrue(machine.isFinished());
        assertEquals(Arrays.asList("hold", "release"), events);
        assertEquals(3, readings.get(), "the condition is still read after every loop");
    }

    @Test
    @DisplayName("releasing the mechanism even when the auto is aborted mid-hold")
    void releasesWhenTheAutoIsAborted() {
        HoldState hold = HoldState.forSeconds("Intake on", 30, on -> events.add(on ? "hold" : "release"));

        StateMachine machine = new StateMachine(hold);
        machine.start();
        machine.update();
        machine.stop();

        assertEquals(Arrays.asList("hold", "release"), events);
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("rejects bad arguments instead of failing mid-match")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> HoldState.forSeconds("Bad", -1, on -> {}));
        assertThrows(NullPointerException.class, () -> HoldState.forSeconds("Bad", 1, null));
        assertThrows(NullPointerException.class, () -> HoldState.until("Bad", null, on -> {}));
        assertThrows(NullPointerException.class, () -> HoldState.until(null, () -> true, on -> {}));
    }

    @Test
    @DisplayName("shows its given name on the Driver Station")
    void reportsItsName() {
        assertEquals("Intake on", HoldState.forSeconds("Intake on", 1, on -> {}).name());
    }
}
