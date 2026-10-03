package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the runner itself, with no robot, no driver station and no Android. Assertions are made
 * against an ordered log of lifecycle callbacks, because the ordering is the contract.
 */
class StateMachineTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("runs the states in order, looping each until its own end condition is met")
    void runsStatesInOrder() {
        StateMachine machine =
                new StateMachine(RecordingState.lasting("a", log, 2), RecordingState.lasting("b", log, 1));

        machine.start();
        machine.update();
        machine.update();
        machine.update();
        machine.update();

        assertEquals(Arrays.asList("a.init", "a.loop", "a.loop", "a.stop", "b.init", "b.loop", "b.stop"), log);
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("stops the current state before initialising the next one")
    void stopsBeforeTheNextInit() {
        StateMachine machine =
                new StateMachine(RecordingState.lasting("a", log, 1), RecordingState.lasting("b", log, 1));

        runToCompletion(machine);

        assertEquals(Arrays.asList("a.stop", "b.init"), log.subList(2, 4));
    }

    @Test
    @DisplayName("loops every state at least once, even when its end condition is already true")
    void loopsAtLeastOncePerState() {
        StateMachine machine = new StateMachine(new RecordingState("a", log, () -> true));

        machine.start();
        machine.update();

        assertEquals(Arrays.asList("a.init", "a.loop", "a.stop"), log);
        assertTrue(machine.isFinished());
    }

    @Test
    @DisplayName("checks the end condition after every loop, and never before the first one")
    void checksTheEndConditionOnlyAfterLooping() {
        AtomicInteger checks = new AtomicInteger();
        StateMachine machine = new StateMachine(
                new RecordingState("a", log, () -> {
                    checks.incrementAndGet();
                    return false;
                }));

        machine.start();
        assertEquals(0, checks.get(), "end condition must not be evaluated before the first loop()");

        machine.update();
        assertEquals(1, checks.get());

        machine.update();
        assertEquals(2, checks.get());
    }

    @Test
    @DisplayName("reordering the route changes the auto without touching any state's internals")
    void reorderingTheRouteChangesTheAuto() {
        State a = RecordingState.lasting("a", log, 1);
        State b = RecordingState.lasting("b", log, 1);
        State c = RecordingState.lasting("c", log, 1);

        runToCompletion(new StateMachine(Arrays.asList(a, b, c)));
        List<String> forwards = new ArrayList<>(log);
        log.clear();

        runToCompletion(new StateMachine(Arrays.asList(c, a, b)));

        assertEquals(
                Arrays.asList(
                        "c.init", "c.loop", "c.stop", "a.init", "a.loop", "a.stop", "b.init", "b.loop", "b.stop"),
                log);
        assertNotEquals(forwards, log, "the same states in a different order must produce a different auto");
    }

    @Test
    @DisplayName("finishes after the last state and ignores any further updates")
    void ignoresUpdatesAfterFinishing() {
        StateMachine machine = new StateMachine(new RecordingState("a", log));

        machine.start();
        machine.update();
        assertTrue(machine.isFinished());
        assertNull(machine.currentState());
        assertEquals(-1, machine.currentIndex());

        machine.update();
        machine.update();

        assertEquals(Arrays.asList("a.init", "a.loop", "a.stop"), log);
    }

    @Test
    @DisplayName("reports the state it is on, so the Driver Station can show progress")
    void reportsProgress() {
        State a = RecordingState.lasting("a", log, 2);
        State b = RecordingState.lasting("b", log, 1);
        StateMachine machine = new StateMachine(Arrays.asList(a, b));

        assertEquals(2, machine.size());
        assertFalse(machine.isStarted());
        assertNull(machine.currentState());
        assertEquals(0.0, machine.elapsedSeconds(), 1e-9);

        machine.start();
        assertTrue(machine.isStarted());
        assertSame(a, machine.currentState());
        assertEquals(0, machine.currentIndex());
    }

    @Test
    @DisplayName("times the auto and the current state from the injected clock")
    void reportsElapsedTime() {
        FakeClock clock = new FakeClock();
        StateMachine machine = new StateMachine(
                Arrays.asList(RecordingState.lasting("a", log, 2), RecordingState.lasting("b", log, 1)), clock);

        machine.start();
        clock.advance(0.5);
        assertEquals(0.5, machine.elapsedSeconds(), 1e-6);
        assertEquals(0.5, machine.currentStateElapsedSeconds(), 1e-6);

        machine.update();
        clock.advance(0.25);
        assertEquals(0.75, machine.elapsedSeconds(), 1e-6);
        assertEquals(0.75, machine.currentStateElapsedSeconds(), 1e-6);

        machine.update();
        clock.advance(0.5);
        assertEquals(1, machine.currentIndex());
        assertEquals(0.5, machine.currentStateElapsedSeconds(), 1e-6, "the state timer restarts for each state");
        assertEquals(1.25, machine.elapsedSeconds(), 1e-6);

        machine.update();
        assertTrue(machine.isFinished());
        assertNull(machine.currentState());
        assertEquals(0.0, machine.currentStateElapsedSeconds(), 1e-6, "no state is active once the auto is done");
    }

    @Test
    @DisplayName("a state that never sets an end condition loops once instead of hanging the auto")
    void aStateWithoutAConditionRunsOnce() {
        AbstractState forgetful = new AbstractState() {
            @Override
            public void loop() {
                log.add("forgetful.loop");
            }
        };

        StateMachine machine = new StateMachine(forgetful);
        machine.start();
        machine.update();

        assertTrue(machine.isFinished());
        assertEquals(Collections.singletonList("forgetful.loop"), log);
    }

    @Test
    @DisplayName("counts time a state spends in init() as time spent in that state")
    void timeSpentInInitCountsTowardsTheState() {
        // init() runs while the Driver Station still shows INIT, so a state can genuinely spend
        // time there. The timer has always started before it, and under-reporting that time
        // would make a slow state look faster than it is.
        FakeClock clock = new FakeClock();
        State slowToStart = new AbstractState() {
            @Override
            public void init() {
                clock.advance(0.25);
            }

            @Override
            public void loop() {
            }
        };

        StateMachine machine = new StateMachine(Arrays.asList(slowToStart), clock);
        machine.start();

        assertEquals(0.25, machine.currentStateElapsedSeconds(), 1e-6,
                "the state timer starts before init(), not after it");
    }

    @Nested
    @DisplayName("aborting")
    class Aborting {

        @Test
        @DisplayName("stops the running state and runs nothing further")
        void stopEndsTheRouteEarly() {
            StateMachine machine =
                    new StateMachine(RecordingState.lasting("a", log, 5), RecordingState.lasting("b", log, 1));

            machine.start();
            machine.update();
            machine.stop();

            assertEquals(Arrays.asList("a.init", "a.loop", "a.stop"), log);
            assertTrue(machine.isFinished());
            assertNull(machine.currentState());

            machine.update();
            assertEquals(3, log.size(), "no state may run after the machine was stopped");
        }

        @Test
        @DisplayName("is safe before start, twice in a row, and after a finished run")
        void stopIsIdempotent() {
            StateMachine notStarted = new StateMachine(new RecordingState("a", log));
            notStarted.stop();
            assertEquals(Collections.emptyList(), log);

            StateMachine machine = new StateMachine(new RecordingState("a", log));
            machine.start();
            machine.stop();
            machine.stop();

            assertEquals(Arrays.asList("a.init", "a.stop"), log);
            assertTrue(machine.isFinished());
        }
    }

    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("updating before start() is an error, not a silent no-op")
        void updateBeforeStartThrows() {
            StateMachine machine = new StateMachine(new RecordingState("a", log));

            IllegalStateException thrown = assertThrows(IllegalStateException.class, machine::update);

            assertTrue(thrown.getMessage().contains("start()"), thrown.getMessage());
        }

        @Test
        @DisplayName("a machine cannot be replayed, because its states carry state")
        void startTwiceThrows() {
            StateMachine machine = new StateMachine(new RecordingState("a", log));
            machine.start();

            IllegalStateException thrown = assertThrows(IllegalStateException.class, machine::start);

            assertTrue(thrown.getMessage().contains("already started"), thrown.getMessage());
        }

        @Test
        @DisplayName("an empty route is rejected at construction, not at the start of a match")
        void emptyRouteIsRejected() {
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> new StateMachine(Collections.emptyList()));

            assertTrue(thrown.getMessage().contains("at least one state"), thrown.getMessage());
        }

        @Test
        @DisplayName("a null state is rejected at construction")
        void nullStateIsRejected() {
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class, () -> new StateMachine(new RecordingState("a", log), null));

            assertTrue(thrown.getMessage().contains("is null"), thrown.getMessage());
        }

        @Test
        @DisplayName("a state that returns a null end condition fails loudly and names itself")
        void nullEndConditionIsReported() {
            State broken =
                    new State() {
                        @Override
                        public void init() {}

                        @Override
                        public void loop() {}

                        @Override
                        public void stop() {}

                        @Override
                        public BooleanSupplier endCondition() {
                            return null;
                        }

                        @Override
                        public String name() {
                            return "BrokenState";
                        }
                    };

            StateMachine machine = new StateMachine(broken);
            machine.start();

            IllegalStateException thrown = assertThrows(IllegalStateException.class, machine::update);

            assertTrue(thrown.getMessage().contains("BrokenState"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("setEndCondition"), thrown.getMessage());
        }
    }

    private static void runToCompletion(StateMachine machine) {
        machine.start();
        for (int guard = 0; guard < 1000 && !machine.isFinished(); guard++) {
            machine.update();
        }
        assertTrue(machine.isFinished(), "the route should have finished within 1000 updates");
    }
}
