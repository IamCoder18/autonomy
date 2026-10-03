package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the timer a group carries, and the {@code exitWhen} that lets a timeout actually end a
 * phase.
 *
 * <p>A group is the case the stopwatch half of {@link Timer} was designed for: "how long did this
 * phase take" is a question about a group, and its children each only know about their own step.
 * The timeout half is the case that needs care -- leaving a group early means stopping whichever
 * child was running, so a phase bounded by a clock cannot leave a mechanism running.
 */
class SubmachineTimerTest {

    /** The default exit condition, matching the framework's: never leave early. */
    private static final java.util.function.BooleanSupplier NEVER = () -> false;

    private final List<String> log = new ArrayList<>();

    /** A group that starts its own timer on entry and leaves when it runs out. */
    private class TimedSubmachine extends Submachine {

        TimedSubmachine(String id, LongSupplier nanoTime, State... children) {
            super(id, nanoTime, children);      // the timer reads the test's clock
        }

        @Override
        public void init() {
            log.add(name() + ".init");
            startTimer(0.5);
            exitWhen(timer()::hasElapsed);
        }

        @Override
        public void stop() {
            log.add(name() + ".stop");
        }
    }

    private StateMachine machineWith(FakeClock clock, State... states) {
        return new StateMachine(Arrays.asList(states), clock);
    }

    @Nested
    @DisplayName("as a stopwatch")
    class AsAStopwatch {

        @Test
        @DisplayName("measures the whole phase, not one step of it")
        void measuresTheWholePhase() {
            FakeClock clock = new FakeClock();
            TimingPhase phase = new TimingPhase("Climb", clock,
                    new WaitState("Reach", 1.0, clock),
                    new WaitState("Pull", 1.0, clock));
            StateMachine machine = machineWith(clock, phase);

            machine.start();
            assertTrue(phase.timer().isRunning(), "the group's init() runs as the machine starts");
            assertEquals(0.0, phase.timer().elapsedSeconds(), 1e-9,
                    "the phase clock starts at zero, having not counted the driver's time in INIT");

            machine.update();                      // Reach arms its own deadline
            clock.advance(1.0);
            machine.update();                      // ... Reach ends, Pull is entered and arms
            machine.update();
            clock.advance(1.0);
            machine.update();

            assertTrue(machine.isFinished());
            assertEquals(2.0, phase.elapsedAtStop, 1e-9,
                    "the phase took both of its steps, and the group clock counted both");
        }

        @Test
        @DisplayName("keeps counting across nested groups, so a phase reports its true cost")
        void countsNestedGroupsToo() {
            FakeClock clock = new FakeClock();
            TimingPhase outer = new TimingPhase("Match", clock,
                    new TimingPhase("Red", clock, new WaitState("a", 0.5, clock)),
                    new TimingPhase("Blue", clock, new WaitState("b", 0.5, clock)));
            StateMachine machine = machineWith(clock, outer);

            // Driven a step at a time rather than in a loop, because the total depends on the
            // one-iteration-per-transition rule: a state is entered on one update() and gets its
            // first loop() on the next, so "b" is entered at 0.5 s and arms its wait at 1.0 s.
            machine.start();
            machine.update();                      // "a" arms its wait at 0.0
            clock.advance(0.5);
            machine.update();                      // "a" ends; Blue and "b" are entered
            clock.advance(0.5);                    // 1.0: this update gives "b" its first loop()
            machine.update();                      // "b" arms its wait at 1.0
            clock.advance(0.5);
            machine.update();                      // "b" ends, and the match phase stops

            assertTrue(machine.isFinished());
            assertEquals(1.5, outer.elapsedAtStop, 1e-9,
                    "the outer phase ran for all three half seconds, including the iteration each "
                            + "nested group spent being entered");
        }
    }

    @Nested
    @DisplayName("as a timeout on the phase")
    class AsAPhaseTimeout {

        @Test
        @DisplayName("leaves the phase when the clock runs out, without waiting for the last step")
        void leavesThePhaseWhenTheClockRunsOut() {
            FakeClock clock = new FakeClock();
            TimedSubmachine phase = new TimedSubmachine("Climb", clock,
                    new WaitState("Reach", 30.0, clock),      // would otherwise run for 30 s
                    RecordingState.lasting("never", log, 1000));
            StateMachine machine = machineWith(clock, phase);

            machine.start();
            machine.update();
            clock.advance(0.5);
            machine.update();

            assertTrue(machine.isFinished(), "the phase should end on its 0.5 s budget, not on its steps");
        }

        @Test
        @DisplayName("stops the step that was running, so it releases whatever it was holding")
        void stopsTheRunningChild() {
            FakeClock clock = new FakeClock();
            TimedSubmachine phase = new TimedSubmachine("Climb", clock,
                    new WaitState("Reach", 0.1, clock),
                    RecordingState.lasting("holding", log, 1000));
            StateMachine machine = machineWith(clock, phase);

            machine.start();
            machine.update();                      // Reach arms its wait
            clock.advance(0.1);
            machine.update();                      // Reach ends, "holding" is entered
            clock.advance(0.4);                    // 0.5 s: the phase budget is now spent
            machine.update();                      // ... while "holding" is mid-run

            assertTrue(log.contains("holding.stop"),
                    "a step running when the phase timed out must still be stopped, or a mechanism "
                            + "it was holding would still be on at the end of the match");
            assertTrue(log.contains("Climb.stop"), "the group itself stops too");
            assertTrue(log.indexOf("holding.stop") < log.indexOf("Climb.stop"),
                    "the child must be stopped before the group that contained it");
        }

        @Test
        @DisplayName("is not inherited by a step entered after it, which would end on arrival")
        void doesNotStartLaterStepsAlreadyExpired() {
            FakeClock clock = new FakeClock();
            // A state that would run one loop and finish anyway, so the only thing that can end
            // this phase early is the group's own budget.
            TimedSubmachine phase = new TimedSubmachine("Climb", clock,
                    new WaitState("Reach", 0.1, clock),
                    RecordingState.lasting("last", log, 5));
            StateMachine machine = machineWith(clock, phase);

            machine.start();
            machine.update();
            clock.advance(0.1);
            machine.update();                      // Reach ends, "last" is entered
            machine.update();                      // ... and gets its first loop()

            assertTrue(log.contains("last.loop"), "the last step should get to run at least once");
        }

        @Test
        @DisplayName("keeps the exit condition the group was built with, rather than replacing it")
        void keepsTheOriginalExitCondition() {
            FakeClock clock = new FakeClock();
            AtomicBoolean goalSeen = new AtomicBoolean(false);
            Submachine phase = new Submachine("Climb",
                    Arrays.asList(RecordingState.lasting("a", log, 1000)), goalSeen::get, clock) {
                @Override
                public void init() {
                    startTimer(30.0);               // far longer than this test will run
                    exitWhen(timer()::hasElapsed);
                }
            };
            StateMachine machine = machineWith(clock, phase);

            machine.start();
            machine.update();
            goalSeen.set(true);
            machine.update();

            assertTrue(machine.isFinished(),
                    "the condition the group was constructed with must still end it, or adding a "
                            + "timeout to a phase that leaves on sight would throw that away");
        }

        @Test
        @DisplayName("does not carry one entry's conditions into the next entry of the same group")
        void doesNotLeakConditionsBetweenEntries() {
            // A group instance can legitimately appear more than once in a route -- nothing stops a
            // team reusing one, and children() is immutable -- so init() may run more than once on
            // the same object. A condition added by the first entry must not still be live during
            // the second: the route would cut short, for a reason belonging to a previous entry.
            FakeClock clock = new FakeClock();
            boolean[] gate = new boolean[2];
            int[] entries = new int[1];
            Submachine shared = new Submachine("Shared",
                    Arrays.asList(new WaitState("a", 0.0, clock), new WaitState("b", 0.0, clock)),
                    NEVER, clock) {
                @Override
                public void init() {
                    startTimer(30.0);
                    int entry = entries[0]++;
                    // Clamped rather than indexed: this test is about which conditions are live,
                    // not how many times a group is entered, and running off the end of the array
                    // would fail it for an unrelated reason.
                    exitWhen(() -> entry < gate.length && gate[entry]);
                }
            };
            StateMachine machine = new StateMachine(Arrays.asList(shared, shared), clock);

            machine.start();
            // Step until the first entry is over and the second has begun, which is the only moment
            // this test is about: a condition set now must not affect the entry already under way.
            while (entries[0] < 2 && !machine.isFinished()) {
                machine.update();
            }
            assertEquals(2, entries[0], "both entries should have begun");

            // True only now, after the first entry ended. If it were still registered, it would end
            // the second entry immediately.
            gate[0] = true;
            machine.update();

            assertFalse(machine.isFinished(),
                    "the second entry ended because of a condition registered during the first; a "
                            + "reused group must start each entry from the conditions it was built with");
        }

        @Test
        @DisplayName("rejects a null condition, rather than failing later on every iteration")
        void rejectsANullCondition() {
            FakeClock clock = new FakeClock();
            Submachine phase = new Submachine("Climb", Arrays.asList(
                    RecordingState.lasting("a", log, 1)), NEVER, clock) {
                @Override
                public void init() {
                    startTimer(30.0);
                    assertThrows(NullPointerException.class, () -> exitWhen(null),
                            "a group that forgot to pass a condition should say so at once");
                }
            };

            machineWith(clock, phase).start();
        }
    }

    /** A group that only times itself, so the elapsed time can be read once it has stopped. */
    private static class TimingPhase extends Submachine {

        double elapsedAtStop = -1.0;

        TimingPhase(String id, LongSupplier nanoTime, State... children) {
            super(id, nanoTime, children);
        }

        @Override
        public void init() {
            startTimer();
        }

        @Override
        public void stop() {
            elapsedAtStop = timer().elapsedSeconds();
        }
    }
}