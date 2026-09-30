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
    @DisplayName("holds on every loop and releases exactly once, when the state ends")
    void holdsThenReleases() {
        FakeClock clock = new FakeClock();
        HoldState hold = HoldState.forSeconds("Intake on", 0.5, on -> events.add(on ? "hold" : "release"), clock);

        StateMachine machine = new StateMachine(Arrays.asList(hold), clock);
        machine.start();

        clock.advance(0.125);
        machine.update();
        clock.advance(0.125);
        machine.update();
        assertEquals(Arrays.asList("hold", "hold"), events);
        assertFalse(machine.isFinished());

        clock.advance(0.25);
        machine.update();
        assertEquals(Arrays.asList("hold", "hold", "hold", "release"), events);
        assertTrue(machine.isFinished());
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
        assertEquals(Arrays.asList("hold", "hold", "hold", "release"), events);
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
