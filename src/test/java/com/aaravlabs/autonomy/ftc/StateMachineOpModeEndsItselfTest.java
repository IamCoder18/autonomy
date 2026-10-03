package com.aaravlabs.autonomy.ftc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aaravlabs.autonomy.AbstractState;
import com.aaravlabs.autonomy.State;
import com.aaravlabs.autonomy.Submachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a finished routine ends its OpMode, and that a silently-skipped state says so.
 *
 * <p><strong>The hung-autonomous failure.</strong> The SDK drives an OpMode roughly as:
 *
 * <pre>{@code
 * init(); start();
 * while (!stopRequested) { loop(); Thread.sleep(1); }
 * stop();
 * }</pre>
 *
 * <p>{@code stop()} is reached only once something raises {@code stopRequested}. A state machine
 * that runs out of states but never asks to be stopped therefore loops forever: the Driver Station
 * shows the OpMode as still RUNNING, telemetry is frozen on {@code Auto: DONE}, and nothing
 * happens until the Robot Controller force-kills it at the match timer. The hardware is already
 * released -- the last state's {@code stop()} ran -- which is why it is so easy to miss in testing:
 * the robot looks idle and correct and the auto simply never ends.
 *
 * <p><strong>What is verified where.</strong> {@link #shouldEndTheOpMode()} is package private and
 * free of SDK calls, so the decision is tested here for real. The SDK call it drives is
 * {@code final} in the real SDK and reaches straight into Robot Controller internals, so it cannot
 * be invoked on a desktop JVM; {@link SdkReferenceTest} pins its descriptor and fails if the call
 * is ever deleted from the compiled adapter, since javac then drops the reference from the constant
 * pool entirely.
 */
class StateMachineOpModeEndsItselfTest {

    private static final String REQUEST_STOP = "requestOpModeStop";

    @Test
    @DisplayName("StateMachineOpMode inherits the SDK's stop request")
    void inheritsAStopRequest() throws Exception {
        // Without this method the adapter has no way to end its own OpMode, so the call cannot
        // even be written. It is declared on the package-private OpModeInternal and reached
        // through the public OpMode, which is legal for both javac and the JVM.
        Method found = null;
        for (Class<?> type = StateMachineOpMode.class; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(REQUEST_STOP)) {
                    found = method;
                }
            }
        }

        assertTrue(found != null, "no requestOpModeStop() anywhere up the OpMode hierarchy. Every"
                + " autonomous would run until the Robot Controller killed it.");

        assertEquals(void.class, found.getReturnType(),
                "the SDK's requestOpModeStop() returns void; if this changes, the stub does too");
        assertTrue(java.lang.reflect.Modifier.isFinal(found.getModifiers()),
                "requestOpModeStop() is final in the real SDK. If it stops being final, the stub"
                        + " has drifted and this test should stop asserting it");
    }

    @Test
    @DisplayName("ends the OpMode once the route is exhausted")
    void endsTheOpModeWhenTheRouteIsFinished() {
        ProbeOpMode opMode = new ProbeOpMode(new OneLoopState("only"));
        opMode.runInit();

        assertFalse(opMode.shouldEndTheOpMode(), "nothing has run yet");

        opMode.runLoop();

        assertTrue(opMode.shouldEndTheOpMode(),
                "the route's only state has finished, so the OpMode should end");
        assertTrue(opMode.stopRequested,
                "onSafeLoop must actually reach requestOpModeStop(). A predicate that is merely"
                        + " correct is not enough; without the call the OpMode still never ends.");
    }

    @Test
    @DisplayName("does not end the OpMode while states remain")
    void doesNotEndEarly() {
        ProbeOpMode opMode = new ProbeOpMode(
                new OneLoopState("first"), new OneLoopState("second"), new OneLoopState("third"));
        opMode.runInit();

        // Each of these states ends on its first loop, and the machine enters the next one in
        // the same update, so one iteration per state: three updates and it is done.
        opMode.runLoop();
        assertEquals("second", opMode.machine().currentState().name());
        assertFalse(opMode.shouldEndTheOpMode(),
                "the OpMode must not end while the route still has states to run");

        opMode.runLoop();
        assertEquals("third", opMode.machine().currentState().name());
        assertFalse(opMode.shouldEndTheOpMode(),
                "still two states to go; ending here would cut the route short");

        opMode.runLoop();
        assertTrue(opMode.shouldEndTheOpMode(), "the last state is done, so the OpMode should end");
        assertTrue(opMode.stopRequested, "and the Robot Controller must have been asked to end it");
    }

    @Test
    @DisplayName("warns on telemetry when a state never set an end condition")
    void flagsAStateThatRanOnceByAccident() {
        // A state that forgets setEndCondition in init() loops exactly once and the route
        // moves on. That is the documented behaviour, and it is the same shape as the
        // "state got skipped" bug this library exists to design out -- so it must be visible
        // on the Driver Station rather than inferred from a route that finished early.
        ProbeOpMode opMode = new ProbeOpMode(new ForgetfulState());
        opMode.runInit();
        opMode.runLoop();

        assertTrue(opMode.telemetryLines().stream().anyMatch(line -> line.contains("State problem")),
                "a state that never set an end condition must be flagged. Saw: "
                        + opMode.telemetryLines());
    }

    @Test
    @DisplayName("stays quiet when states do set their end conditions")
    void doesNotWarnAboutWellBehavedStates() {
        ProbeOpMode opMode = new ProbeOpMode(new OneLoopState("first"), new OneLoopState("second"));
        opMode.runInit();

        for (int i = 0; i < 4; i++) {
            opMode.runLoop();
        }

        assertTrue(opMode.telemetryLines().stream().noneMatch(line -> line.contains("State problem")),
                "well-behaved states must not produce a warning: " + opMode.telemetryLines());
        assertTrue(opMode.telemetryLines().stream().anyMatch(line -> line.contains("Auto=DONE")),
                "the last line of a finished auto should say so: " + opMode.telemetryLines());
    }

    @Test
    @DisplayName("stays quiet about groups, which have no end condition to forget")
    void doesNotWarnAboutGroups() {
        // This is the reason Submachine implements State rather than extending AbstractState.
        // A group's end condition is "never leave early", not something a missing
        // setEndCondition call could explain, so extending AbstractState would make every
        // nested route report "no end condition, ran one loop" on the Driver Station -- a
        // permanent false alarm on the most common state in a structured route. If someone
        // refactors Submachine onto AbstractState, this test fails.
        ProbeOpMode opMode = new ProbeOpMode(new Submachine("phase",
                new OneLoopState("inner-a"), new OneLoopState("inner-b")));
        opMode.runInit();

        for (int i = 0; i < 4; i++) {
            opMode.runLoop();
        }

        assertTrue(opMode.telemetryLines().stream().noneMatch(line -> line.contains("State problem")),
                "a group must not be reported as a state that forgot its end condition: "
                        + opMode.telemetryLines());
        assertTrue(opMode.shouldEndTheOpMode(), "a nested route must still finish");
    }

    @Test
    @DisplayName("shows the step, the phase it belongs to, and the top-level position")
    void reportsNestedProgressOnTelemetry() {
        // Three steps behind two groups. "Auto" counts top-level entries, so it reads 1/1 for
        // the whole run -- useless for "how far through the auto are we", which is why "Step"
        // exists alongside it.
        ProbeOpMode opMode = new ProbeOpMode(new Submachine("phase",
                new Submachine("inner", new OneLoopState("first"), new OneLoopState("second")),
                new OneLoopState("third")));
        opMode.runInit();

        // One update retires "first" and moves to "second", still two groups deep.
        opMode.runLoop();

        List<String> lines = opMode.telemetryLines();
        assertTrue(lines.contains("Auto=1/1"), "the route has one top-level entry: " + lines);
        assertTrue(lines.contains("Step=2/3"), "the second of three steps: " + lines);
        assertTrue(lines.contains("State=second"), "the leaf is what is running: " + lines);
        assertTrue(lines.contains("Phase=phase > inner"), "and which phase it is in: " + lines);

        for (int i = 0; i < 4; i++) {
            opMode.runLoop();
        }
        assertTrue(opMode.telemetryLines().contains("Auto=DONE"),
                "a nested route ends the same way a flat one does: " + opMode.telemetryLines());
    }

    @Test
    @DisplayName("says nothing is wrong before init() has run")
    void isQuietBeforeInit() {
        // Synapse's SafeOpMode.init() builds the orchestrator and only then calls
        // onSafeInit(), so there is a window in which there is no machine at all. Nothing
        // should NPE or misreport in it.
        ProbeOpMode opMode = new ProbeOpMode(new OneLoopState("only"));

        assertFalse(opMode.shouldEndTheOpMode());
    }

    // ---- doubles ---------------------------------------------------------------

    /**
     * A real {@link StateMachineOpMode} subclass, driven the way Synapse drives it.
     *
     * <p>It genuinely extends the adapter, which is the point: the point of these tests is the
     * adapter's own control flow, so mocking it away would test nothing. Instantiating it is
     * safe because {@code SafeOpMode}'s constructor only chains to {@code OpMode}'s -- the Android
     * work happens in {@code SafeOpMode.init()}, which is never called here. {@code onSafeInit}
     * and {@code onSafeLoop} are invoked reflectively because they are {@code protected}, and the
     * stop request cannot run off a Robot Controller, so {@link #runLoop()} treats reaching it as
     * the signal it is and records it as {@link #stopRequested}.
     */
    private static final class ProbeOpMode extends StateMachineOpMode {

        final List<State> route;
        final RecordingTelemetry recorder = new RecordingTelemetry();

        /** True once the adapter has reached its own {@code endTheRoutine()}. */
        boolean stopRequested;

        ProbeOpMode(State... route) {
            this.route = Arrays.asList(route);
            installTelemetry();
        }

        private void installTelemetry() {
            try {
                // telemetry is declared on the SDK's package-private OpModeInternal and
                // assigned there by the Robot Controller, which is not running here.
                Field field = null;
                for (Class<?> type = getClass(); type != null && field == null;
                        type = type.getSuperclass()) {
                    try {
                        field = type.getDeclaredField("telemetry");
                    } catch (NoSuchFieldException ignored) {
                        // keep walking up
                    }
                }
                if (field == null) {
                    throw new IllegalStateException("no telemetry field on the OpMode hierarchy");
                }
                field.setAccessible(true);
                field.set(this, recorder);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not install a recording Telemetry", e);
            }
        }

        @Override
        protected List<State> buildStates() {
            return route;
        }

        List<String> telemetryLines() {
            return recorder.lines;
        }

        /** Runs {@code onSafeInit()}, standing in for Synapse calling it from init(). */
        void runInit() {
            invoke("onSafeInit");
        }

        /**
         * Runs one {@code onSafeLoop()} iteration.
         *
         * <p>{@code requestOpModeStop()} goes straight into Robot Controller internals -- it
         * calls {@code internalOpModeServices}, which only exists while an OpMode is registered --
         * so off the robot it fails on arrival. That failure is the boundary this harness stops
         * at, and it is a useful one: reaching it <em>is</em> the assertion, because it can only
         * happen after {@code endTheRoutine()}. So it is recorded as {@link #stopRequested}
         * rather than swallowed or rethrown, and a failure from anywhere else is rethrown so a
         * real bug is not mistaken for the expected one.
         */
        void runLoop() {
            if (stopRequested) {
                return;
            }
            try {
                invoke("onSafeLoop");
            } catch (IllegalStateException e) {
                if (reachedStopRequest(e)) {
                    stopRequested = true;
                } else {
                    // Not the failure we were braced for. Rethrowing it here rather than
                    // swallowing it keeps a genuine bug from reading as "the stop was
                    // requested", and the wrapper's own message points at the real cause.
                    throw new IllegalStateException(
                            "onSafeLoop failed for a reason other than the stop request, so"
                                    + " this test cannot tell whether it was reached. The"
                                    + " original failure follows.", e);
                }
            }
        }

        /**
         * Whether this failure is the expected one: the stop request reaching the SDK stub.
         *
         * <p>Two conditions, not one. The stub's {@code requestOpModeStop()} throws
         * {@link UnsupportedOperationException}, so requiring that in the cause chain is what
         * makes the match specific. A single stack-frame check on {@code endTheRoutine} would
         * also flip on any unrelated failure that happened to pass through that method, and
         * would break -- silently, by recording the wrong answer -- if the method were renamed
         * or inlined. Matching the exception type keeps the coupling on the SDK boundary, which
         * is the thing this test is actually asserting.
         */
        private static boolean reachedStopRequest(Throwable failure) {
            boolean sawStubThrow = false;
            boolean passedThrough = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof UnsupportedOperationException) {
                    sawStubThrow = true;
                }
                for (StackTraceElement frame : cause.getStackTrace()) {
                    if (frame.getMethodName().equals("endTheRoutine")) {
                        passedThrough = true;
                    }
                }
            }
            return sawStubThrow && passedThrough;
        }

        private void invoke(String hook) {
            try {
                Method method = StateMachineOpMode.class.getDeclaredMethod(hook);
                method.setAccessible(true);
                method.invoke(this);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not invoke " + hook, e);
            }
        }
    }

    /** Captures the lines the adapter would send to the Driver Station. */
    private static final class RecordingTelemetry implements Telemetry {

        final List<String> lines = new ArrayList<>();

        private Item add(String caption, Object value) {
            lines.add(caption + "=" + value);
            return new RecordingItem();
        }

        @Override
        public Item addData(String cap, Object format) {
            return add(cap, format);
        }

        @Override
        public Item addData(String cap, String format, Object... args) {
            return add(cap, String.format(format, args));
        }

        @Override
        public boolean update() {
            return true;
        }

        private final class RecordingItem implements Telemetry.Item {

            @Override
            public Item setCaption(String caption) {
                return this;
            }

            @Override
            public Item setValue(String value, Object... args) {
                return this;
            }

            @Override
            public Item setValue(Object value) {
                return this;
            }

            @Override
            public Item setRetained(Boolean retained) {
                return this;
            }
        }
    }

    /** Loops once, then finishes: the well-behaved case. */
    private static final class OneLoopState extends AbstractState {

        private final String name;
        private int loops;

        OneLoopState(String name) {
            this.name = name;
        }

        @Override
        public void init() {
            setEndCondition(() -> loops >= 1);
        }

        @Override
        public void loop() {
            loops++;
        }

        @Override
        public String name() {
            return name;
        }
    }

    /** Never sets an end condition, so it runs once and the route skips past it. */
    private static final class ForgetfulState extends AbstractState {

        @Override
        public void loop() {
        }

        @Override
        public String name() {
            return "Forgetful";
        }
    }
}