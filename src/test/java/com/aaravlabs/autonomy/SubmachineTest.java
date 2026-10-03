package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nesting behaviour: groups of states, groups inside groups, and what it costs.
 *
 * <p>Assertions are made against an ordered log of lifecycle callbacks, because the ordering is
 * the contract -- and because the guarantee that has to survive nesting is that entering and
 * leaving a tree of groups is a single transition, not one per level.
 */
class SubmachineTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("runs a group as the sequence of states it holds")
    void runsAGroupAsItsChildren() {
        StateMachine machine = new StateMachine(
                RecordingState.lasting("a", log, 1),
                new RecordingSubmachine("group", log,
                        RecordingState.lasting("b", log, 1),
                        RecordingState.lasting("c", log, 1)),
                RecordingState.lasting("d", log, 1));

        machine.start();
        runToCompletion(machine);

        assertEquals(
                Arrays.asList(
                        "a.init", "a.loop", "a.stop",
                        "group.init", "b.init",
                        "group.loop", "b.loop", "b.stop", "c.init",
                        "group.loop", "c.loop", "c.stop", "group.stop",
                        "d.init", "d.loop", "d.stop"),
                log);
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("retires every enclosing group in one update, however deep the nesting")
    void nestingCostsNoExtraIterations() {
        StateMachine machine = new StateMachine(new RecordingSubmachine("l1", log,
                new RecordingSubmachine("l2", log,
                        new RecordingSubmachine("l3", log, RecordingState.lasting("x", log, 1)))));

        machine.start();
        machine.update();

        assertTrue(machine.isFinished(), "a depth-3 tree must finish in the same one update as a flat route");
        assertEquals(
                Arrays.asList(
                        "l1.init", "l2.init", "l3.init", "x.init",
                        "l1.loop", "l2.loop", "l3.loop", "x.loop", "x.stop",
                        "l3.stop", "l2.stop", "l1.stop"),
                log);
    }

    @Test
    @DisplayName("initialises outermost first and stops innermost first")
    void entersOutsideInAndLeavesInsideOut() {
        StateMachine machine = new StateMachine(new RecordingSubmachine("outer", log,
                new RecordingSubmachine("inner", log, RecordingState.lasting("x", log, 1))));

        machine.start();
        machine.update();

        assertTrue(log.indexOf("outer.init") < log.indexOf("inner.init"));
        assertTrue(log.indexOf("inner.init") < log.indexOf("x.init"));
        assertTrue(log.indexOf("x.stop") < log.indexOf("inner.stop"));
        assertTrue(log.indexOf("inner.stop") < log.indexOf("outer.stop"));
    }

    @Test
    @DisplayName("nests as deeply as a route is built, with no fixed limit")
    void nestsWithoutLimit() {
        State nested = RecordingState.lasting("leaf", log, 1);
        for (int level = 0; level < 50; level++) {
            nested = new RecordingSubmachine("g" + level, log, nested);
        }
        StateMachine machine = new StateMachine(nested);

        assertEquals(1, machine.stepCount(), "fifty groups around one state is still one step");

        machine.start();
        machine.update();

        assertTrue(machine.isFinished());
        assertEquals(1, count(log, "leaf.init"));
        assertEquals(1, count(log, "leaf.stop"));
        assertEquals(1, count(log, "g49.stop"), "the outermost group stops once, last");
    }

    @Test
    @DisplayName("the running state is never a group, however the route is nested")
    void currentStateIsAlwaysARealState() {
        StateMachine machine = new StateMachine(new RecordingSubmachine("group", log,
                new RecordingSubmachine("inner", log, RecordingState.lasting("x", log, 5))));

        machine.start();
        for (int i = 0; i < 4; i++) {
            assertFalse(machine.currentState() instanceof Submachine, "a group is a phase, not a step");
            machine.update();
        }

        assertEquals("x", machine.currentState().name());
    }

    @Nested
    @DisplayName("leaving early")
    class LeavingEarly {

        @Test
        @DisplayName("a group ends as soon as its condition is true, without running its rest")
        void aGroupCanLeaveEarly() {
            AtomicBoolean reachedGoal = new AtomicBoolean();
            StateMachine machine = new StateMachine(new RecordingSubmachine("group", log,
                    reachedGoal::get,
                    Arrays.asList(
                            RecordingState.lasting("live", log, 5),
                            RecordingState.lasting("never", log, 1))));

            machine.start();
            machine.update();
            assertFalse(machine.isFinished());

            reachedGoal.set(true);
            machine.update();

            assertTrue(machine.isFinished());
            assertFalse(log.contains("never.init"), "the group left before reaching its second child");
            assertEquals(
                    Arrays.asList(
                            "group.init", "live.init",
                            "group.loop", "live.loop",
                            "group.loop", "live.loop", "live.stop", "group.stop"),
                    log);
        }

        @Test
        @DisplayName("leaving early stops the state the group was running, so nothing is left held")
        void leavingEarlyStopsTheStateItWasRunning() {
            AtomicBoolean reachedGoal = new AtomicBoolean();
            StateMachine machine = new StateMachine(new RecordingSubmachine("outer", log,
                    reachedGoal::get,
                    Arrays.asList(
                            new RecordingSubmachine("inner", log, RecordingState.lasting("live", log, 5)),
                            RecordingState.lasting("never", log, 1))));

            machine.start();
            machine.update();
            reachedGoal.set(true);
            machine.update();

            assertTrue(machine.isFinished());
            assertTrue(log.contains("live.stop"), "the running state must be released on the way out");
            assertTrue(log.contains("inner.stop"), "the group nested inside must be released too");
            assertTrue(log.contains("outer.stop"));
            assertFalse(log.contains("never.init"));
        }

        @Test
        @DisplayName("a group that is already true on entry still runs the state it entered")
        void anAlreadyTrueGroupDoesNotSkipItsFirstState() {
            StateMachine machine = new StateMachine(new RecordingSubmachine("group", log,
                    () -> true,
                    Arrays.asList(RecordingState.lasting("child", log, 1))));

            machine.start();
            runToCompletion(machine);

            // The child was initialised, so it has to be stopped; skipping its body would mean
            // initialising a state with nothing left to release it.
            assertEquals(Arrays.asList("group.init", "child.init", "group.loop", "child.loop", "child.stop", "group.stop"), log);
            assertTrue(machine.isFinished());
        }
    }

    @Nested
    @DisplayName("stopping the route")
    class StoppingTheRoute {

        @Test
        @DisplayName("unwinds every level it entered, innermost first")
        void stopUnwindsEveryLevel() {
            StateMachine machine = new StateMachine(new RecordingSubmachine("l1", log,
                    RecordingState.lasting("done", log, 1),
                    new RecordingSubmachine("l2", log, RecordingState.lasting("live", log, 5))));

            machine.start();
            machine.update();
            machine.stop();

            assertTrue(machine.isFinished());
            assertNull(machine.currentState());
            assertEquals(
                    Arrays.asList(
                            "l1.init", "done.init",
                            "l1.loop", "done.loop", "done.stop", "l2.init", "live.init",
                            "live.stop", "l2.stop", "l1.stop"),
                    log);
        }

        @Test
        @DisplayName("does not stop a state a second time on the way out")
        void stopDoesNotRetireCompletedStatesTwice() {
            StateMachine machine = new StateMachine(new RecordingSubmachine("group", log,
                    RecordingState.lasting("done", log, 1),
                    RecordingState.lasting("live", log, 5)));

            machine.start();
            machine.update();
            machine.stop();

            assertEquals(1, count(log, "done.stop"), "a state that already finished keeps its one stop");
            assertEquals(1, count(log, "live.stop"));
            assertEquals(1, count(log, "group.stop"));
        }
    }

    @Nested
    @DisplayName("reporting progress")
    class ReportingProgress {

        private StateMachine machine;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            machine = new StateMachine(
                    RecordingState.lasting("a", log, 2),
                    new RecordingSubmachine("group", log,
                            RecordingState.lasting("b", log, 1),
                            new RecordingSubmachine("inner", log, RecordingState.lasting("c", log, 1))));
        }

        @Test
        @DisplayName("counts a group as one step of the route and as however many steps it holds")
        void countsBothTopLevelEntriesAndSteps() {
            assertEquals(2, machine.size(), "two entries at the top level");
            assertEquals(3, machine.stepCount(), "three states once the group is opened up");
        }

        @Test
        @DisplayName("a flat route reports the same numbers under both names")
        void aFlatRouteAgreesWithItself() {
            StateMachine flat = new StateMachine(
                    RecordingState.lasting("a", log, 1), RecordingState.lasting("b", log, 1));

            assertEquals(flat.size(), flat.stepCount());
        }

        @Test
        @DisplayName("reports both positions, so telemetry can show either")
        void reportsTopLevelPositionAndStepPosition() {
            assertEquals(-1, machine.currentIndex());
            assertEquals(-1, machine.currentStepIndex());

            machine.start();
            assertEquals(0, machine.currentIndex(), "the first top-level entry");
            assertEquals(0, machine.currentStepIndex(), "and its first step");
            assertEquals(1, machine.depth(), "not nested");

            // "a" loops twice, so it is still running after the first update.
            machine.update();
            assertEquals(0, machine.currentIndex());

            machine.update();
            assertEquals(1, machine.currentIndex(), "still inside the first top-level entry");
            assertEquals(1, machine.currentStepIndex(), "but on its second step");
            assertEquals(2, machine.depth(), "one group deep");

            machine.update();
            assertEquals(1, machine.currentIndex(), "two groups deep, still the first top-level entry");
            assertEquals(2, machine.currentStepIndex(), "and the third step of the route");
            assertEquals(3, machine.depth());

            runToCompletion(machine);
            assertEquals(-1, machine.currentIndex());
            assertEquals(-1, machine.currentStepIndex());
            assertEquals(0, machine.depth());
        }

        @Test
        @DisplayName("reports the path to the running state, outermost first")
        void reportsThePathToTheRunningState() {
            assertEquals(0, machine.path().size());

            machine.start();
            assertEquals(Arrays.asList("a"), names(machine));

            machine.update();
            assertEquals(Arrays.asList("a"), names(machine), "still on the first step");

            machine.update();
            assertEquals(Arrays.asList("group", "b"), names(machine));

            machine.update();
            assertEquals(Arrays.asList("group", "inner", "c"), names(machine));

            runToCompletion(machine);
            assertEquals(0, machine.path().size());
        }
    }

    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("a group with no children is rejected at construction")
        void anEmptyGroupIsRejected() {
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class, () -> new Submachine("empty", new State[0]));

            assertTrue(thrown.getMessage().contains("at least one child"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("empty"), thrown.getMessage());
        }

        @Test
        @DisplayName("a null child is rejected, and the error names the group")
        void aNullChildIsRejected() {
            RecordingState a = new RecordingState("a", log);

            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class, () -> new Submachine("phase", a, null));

            assertTrue(thrown.getMessage().contains("is null"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("phase"), thrown.getMessage());
        }

        @Test
        @DisplayName("a null name is rejected")
        void aNullNameIsRejected() {
            assertThrows(NullPointerException.class,
                    () -> new Submachine(null, new RecordingState("a", log)));
        }

        @Test
        @DisplayName("a null exit condition is rejected rather than meaning 'never'")
        void aNullExitConditionIsRejected() {
            assertThrows(NullPointerException.class, () -> new Submachine(
                    "g", Arrays.asList(new RecordingState("a", log)), null));
        }

        @Test
        @DisplayName("the same state may appear twice in a route, and runs once per place")
        void theSameStateMayAppearTwice() {
            State shared = RecordingState.lasting("shared", log, 1);
            StateMachine machine = new StateMachine(shared, new RecordingSubmachine("group", log, shared));

            assertEquals(2, machine.stepCount());
            machine.start();
            runToCompletion(machine);

            assertEquals(
                    Arrays.asList("shared.init", "shared.loop", "shared.stop",
                            "group.init", "shared.init", "group.loop", "shared.loop", "shared.stop", "group.stop"),
                    log);
            assertTrue(machine.isFinished());
        }
    }

    private static List<String> names(StateMachine machine) {
        List<String> names = new ArrayList<>();
        for (State state : machine.path()) {
            names.add(state.name());
        }
        return names;
    }

    private static int count(List<String> log, String entry) {
        int total = 0;
        for (String line : log) {
            if (line.equals(entry)) {
                total++;
            }
        }
        return total;
    }

    private static void runToCompletion(StateMachine machine) {
        for (int guard = 0; guard < 1000 && !machine.isFinished(); guard++) {
            machine.update();
        }
        assertTrue(machine.isFinished(), "the route should have finished within 1000 updates");
    }
}