package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldStateTest {

    private final List<String> events = new ArrayList<>();

    @Test
    @DisplayName("applies the mechanism once, and releases exactly once on the way out")
    void holdsThenReleases() {
        FakeClock clock = new FakeClock();
        HoldState hold = HoldState.forSeconds("Intake on", 0.5, on -> events.add(on ? "hold" : "release"), clock);

        StateMachine machine = new StateMachine(Arrays.asList(hold), clock);
        machine.start();
        machine.update();                          // applies the mechanism, arms the deadline

        clock.advance(0.125);
        machine.update();
        assertEquals(Arrays.asList("hold"), events, "applied once, not on every loop");
        assertFalse(machine.isFinished());

        clock.advance(0.25);
        machine.update();
        assertEquals(Arrays.asList("hold"), events, "and still not re-applied");
        assertFalse(machine.isFinished(), "0.375 s of a 0.5 s hold");

        clock.advance(0.125);
        machine.update();
        assertEquals(Arrays.asList("hold", "release"), events);
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("does not power the mechanism while the OpMode is still in INIT")
    void appliesNothingBeforeTheFirstLoop() {
        // init() can run before the OpMode is live: the Driver Station sits in INIT until the
        // driver presses START. Powering a mechanism there means it is already spinning on the
        // bench and before the match is live, which is at best surprising and at worst the reason
        // a robot grabs a wall. The first loop() is the first moment guaranteed to follow START.
        // StateMachineOpMode now enters its route on START rather than INIT, which closes that
        // gap for the OpMode adapter, but this State is driven by a bare StateMachine here so
        // the guarantee has to hold on its own.
        FakeClock clock = new FakeClock();
        List<Boolean> calls = new ArrayList<>();
        HoldState hold = HoldState.forSeconds("Intake", 1, calls::add, clock);

        StateMachine machine = new StateMachine(Arrays.asList(hold), clock);
        machine.start();

        assertEquals(Collections.emptyList(), calls,
                "nothing should be powered between machine.start() and the first update()");

        // The DS can sit in INIT indefinitely. That must not eat into the hold's duration.
        clock.advance(30);
        assertEquals(Collections.emptyList(), calls,
                "still nothing powered 30 simulated seconds into INIT");

        machine.update();
        assertEquals(List.of(true), calls, "and the hold starts when the OpMode actually runs");

        clock.advance(0.5);
        machine.update();
        assertEquals(List.of(true), calls, "and is not re-applied on later iterations");

        clock.advance(0.6);
        machine.update();
        assertEquals(List.of(true, false), calls, "released when the 1 second is up");
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("a short hold still gets its full duration when INIT is long")
    void aShortHoldIsNotSwallowedByInit() {
        // The deadline is armed in init() too, so a hold shorter than the INIT phase would
        // otherwise already be expired when the first loop runs -- the mechanism powered and
        // released within a single iteration, and the step effectively skipped.
        FakeClock clock = new FakeClock();
        List<Boolean> calls = new ArrayList<>();
        HoldState hold = HoldState.forSeconds("Shoot", 0.2, calls::add, clock);

        StateMachine machine = new StateMachine(Arrays.asList(hold), clock);
        machine.start();
        clock.advance(5);          // driver takes 5 seconds to press START
        machine.update();

        assertFalse(machine.isFinished(),
                "a 0.2 s hold must still be pending after a 5 s INIT, not already over");
        assertEquals(List.of(true), calls);

        clock.advance(0.3);
        machine.update();
        assertEquals(List.of(true, false), calls);
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
        assertEquals(Arrays.asList("hold"), events, "applied on the first loop");
        assertFalse(machine.isFinished(), "condition reading 1 of 3");

        machine.update();
        assertFalse(machine.isFinished(), "condition reading 2 of 3");

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
