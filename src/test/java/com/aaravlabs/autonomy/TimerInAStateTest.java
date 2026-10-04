package com.aaravlabs.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the timer a state gets for free, and above all <em>when</em> it starts.
 *
 * <p>{@link StateMachineOpMode} builds a route while the Driver Station shows INIT and enters it
 * only when the driver presses START, and the driver may sit in INIT for an unbounded time first. A
 * timer that began counting when its state was constructed would therefore spend that time before
 * the robot moved -- a 0.2 s shoot timeout could be entirely gone, and the step would never happen.
 * These tests exist so that cannot come back.
 */
class TimerInAStateTest {

    /** A state that times itself, and can be given something to time. */
    private static class TimingState extends AbstractState {

        private final List<String> log;
        private final String label;

        TimingState(String label, LongSupplier nanoTime, List<String> log) {
            super(nanoTime);                   // the timer reads the test's clock, not the real one
            this.label = label;
            this.log = log;
        }

        @Override
        public void init() {
            startTimer(0.2);
            setEndCondition(timer()::hasElapsed);
            log.add(label + ".init at " + timer().elapsedSeconds());
        }

        @Override
        public void loop() {
            log.add(label + ".loop at " + timer().elapsedSeconds());
        }

        @Override
        public String name() {
            return label;
        }
    }

    @Nested
    @DisplayName("when the route is built")
    class WhileBuilding {

        @Test
        @DisplayName("the timer has not started, so it has not been spending the driver's INIT time")
        void hasNotStartedYet() {
            FakeClock clock = new FakeClock();
            TimingState state = new TimingState("Shoot", clock, new ArrayList<>());

            clock.advance(30);                     // the driver takes half a minute to press START

            assertFalse(state.timer().isRunning());
            assertEquals(0.0, state.timer().elapsedSeconds(), 1e-9);
            assertFalse(state.timer().hasElapsed());
        }

        @Test
        @DisplayName("no time is lost even if the state was built long before the machine was started")
        void doesNotLoseTimeBuiltInAdvance() {
            FakeClock clock = new FakeClock();
            List<String> log = new ArrayList<>();
            // The state is built, then sits unused for a while, exactly as one returned from
            // buildStates() does while the Driver Station is still in INIT.
            TimingState state = new TimingState("Shoot", clock, log);
            StateMachine machine = new StateMachine(Arrays.asList(state), clock);

            clock.advance(45);

            machine.start();
            machine.update();

            assertEquals("Shoot.init at 0.0", log.get(0),
                    "the whole 0.2 s timeout should still be ahead of the state");
            assertFalse(machine.isFinished());
        }
    }

    @Nested
    @DisplayName("when the machine starts")
    class WhenStarting {

        @Test
        @DisplayName("the timer starts at zero on the very first iteration, not part way into it")
        void startsAtZeroOnTheFirstIteration() {
            FakeClock clock = new FakeClock();
            List<String> log = new ArrayList<>();
            TimingState state = new TimingState("Shoot", clock, log);
            StateMachine machine = new StateMachine(Arrays.asList(state), clock);

            machine.start();
            machine.update();

            assertEquals("Shoot.init at 0.0", log.get(0));
            assertEquals("Shoot.loop at 0.0", log.get(1));
            assertTrue(state.timer().isRunning());
        }

        @Test
        @DisplayName("the state ends exactly when the timeout is up, counting from START")
        void endsExactlyAtTheTimeout() {
            FakeClock clock = new FakeClock();
            TimingState state = new TimingState("Shoot", clock, new ArrayList<>());
            StateMachine machine = new StateMachine(Arrays.asList(state), clock);

            machine.start();
            machine.update();
            clock.advance(0.19);
            machine.update();
            assertFalse(machine.isFinished(), "0.19 s of a 0.2 s timeout");

            clock.advance(0.01);
            machine.update();
            assertTrue(machine.isFinished(), "the full 0.2 s is up");
        }
    }

    @Nested
    @DisplayName("when the route continues")
    class OnLaterStates {

        @Test
        @DisplayName("a later state's timer starts when that state is entered, not at the start")
        void laterStateStartsItsOwnTimer() {
            FakeClock clock = new FakeClock();
            List<String> log = new ArrayList<>();
            StateMachine machine = new StateMachine(Arrays.asList(
                    new WaitState("Settle", 1.0, clock),
                    new TimingState("Shoot", clock, log)), clock);

            machine.start();
            machine.update();                      // the wait arms its own deadline, from here
            clock.advance(1.0);
            machine.update();                      // the wait ends, the next state is entered
            machine.update();                      // ... and gets its first loop()

            assertTrue(log.get(0).endsWith("Shoot.init at 0.0"),
                    "the shoot should start its own 0.2 s clock, not inherit the wait's 1.0 s");
        }

        @Test
        @DisplayName("two states each get their own timer, so one cannot end the other's timeout")
        void timersAreNotShared() {
            FakeClock clock = new FakeClock();
            TimingState first = new TimingState("First", clock, new ArrayList<>());
            TimingState second = new TimingState("Second", clock, new ArrayList<>());
            StateMachine machine = new StateMachine(Arrays.asList(first, second), clock);

            machine.start();
            machine.update();
            clock.advance(0.2);
            machine.update();                      // First's timeout is up, so Second is entered
            machine.update();                      // ... and gets its first loop()

            assertFalse(second.timer().hasElapsed(),
                    "Second should have a full 0.2 s of its own, not First's elapsed time");
        }
    }

    @Nested
    @DisplayName("when the state is done")
    class WhenFinished {

        @Test
        @DisplayName("the timer reports the time the state took, which is what a postmortem wants")
        void reportsHowLongTheStateTook() {
            FakeClock clock = new FakeClock();
            TimingState state = new TimingState("Shoot", clock, new ArrayList<>());
            StateMachine machine = new StateMachine(Arrays.asList(state), clock);

            machine.start();
            machine.update();
            clock.advance(0.2);
            machine.update();

            assertEquals("Shoot: 0.20 s of 0.20 s", state.timer().toString());
        }

        @Test
        @DisplayName("stopping the timer ends its reporting, so a retired state reads as stopped")
        void stoppingEndsTheReporting() {
            FakeClock clock = new FakeClock();
            StoppingState state = new StoppingState(clock);
            StateMachine machine = new StateMachine(Arrays.asList(state), clock);

            machine.start();
            machine.update();
            clock.advance(0.2);
            machine.update();                      // the timeout is up, so stop() runs

            assertTrue(machine.isFinished());
            assertFalse(state.timer().isRunning());
            assertEquals(0.0, state.timer().elapsedSeconds(), 1e-9);
        }

        @Test
        @DisplayName("stopping the timer ends its reporting even when the machine ends early")
        void stoppingEndsTheReportingOnAnEarlyExit() {
            FakeClock clock = new FakeClock();
            StoppingState state = new StoppingState(clock);
            StateMachine machine = new StateMachine(Arrays.asList(state), clock);

            machine.start();
            machine.update();
            clock.advance(0.1);
            machine.stop();                         // driver hits stop long before the timeout

            assertFalse(state.timer().isRunning());
            assertEquals(0.0, state.timer().elapsedSeconds(), 1e-9);
        }
    }

    /** A state that stops its timer as it exits, as one releasing a mechanism would. */
    private static class StoppingState extends AbstractState {

        StoppingState(LongSupplier nanoTime) {
            super(nanoTime);
        }

        @Override
        public void init() {
            startTimer(0.2);
            setEndCondition(timer()::hasElapsed);
        }

        @Override
        public void loop() {
        }

        @Override
        public void stop() {
            stopTimer();
        }
    }
}