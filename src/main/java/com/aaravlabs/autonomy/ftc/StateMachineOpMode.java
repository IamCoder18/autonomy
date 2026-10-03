package com.aaravlabs.autonomy.ftc;

import com.aaravlabs.synapse.ftc.SafeOpMode;
import com.aaravlabs.autonomy.AbstractState;
import com.aaravlabs.autonomy.State;
import com.aaravlabs.autonomy.StateMachine;
import java.util.ArrayList;
import java.util.List;

/**
 * Base OpMode for autonomous routines that are a sequence of {@link State}s.
 *
 * <p>A subclass does one thing: return its route from {@link #buildStates()}. Everything else --
 * running the sequence, calling the active state's {@code stop()} when the OpMode is interrupted,
 * ending the OpMode once the route is exhausted, and reporting progress to the Driver Station --
 * happens here.
 *
 * <p>The lifecycle hooks {@link #onSafeInit()}, {@link #onSafeStart()} and {@link #onSafeStop()}
 * are {@code final}, because overriding one without calling {@code super} silently breaks the
 * route. To hook the OpMode's lifecycle, use {@link #onRouteInited()} or {@link #onRouteStarted()};
 * to add per-iteration work, override {@link #onSafeLoop()} and call {@code super}.
 *
 * <p>The route is built during INIT and entered when START is pressed, so a state's
 * {@link State#init()} runs with the OpMode live rather than while the Driver Station still shows
 * INIT.
 *
 * <p>Build a fresh machine per run by constructing the states in {@code buildStates()}; do not
 * cache states in fields, because they carry whatever they accumulated on the previous run.
 *
 * <p>This is the only class in Autonomy that depends on Synapse or Android. The state framework
 * itself lives in {@link com.aaravlabs.autonomy} and imports nothing but {@code java.util}.
 */
public abstract class StateMachineOpMode extends SafeOpMode {

    private StateMachine machine;

    /**
     * Names of states seen running on {@link AbstractState}'s run-once default, meaning their
     * {@code init()} never called {@code setEndCondition(...)}.
     *
     * <p>Remembered rather than reported as they happen, because such a state is <em>gone</em> by
     * the time it could be reported: it loops exactly once and the machine moves on, so a check
     * made after {@code update()} would only ever find {@code null}. The warning has to be taken
     * before the update, while the state is still current -- see
     * {@link #noteAnyStateMissingAnEndCondition()}.
     */
    private final List<String> statesMissingAnEndCondition = new ArrayList<>();

    /**
     * Builds the route for this routine, in the order it should run.
     *
     * <p>Called once, after Synapse's hardware map is ready, so states can be handed real devices.
     * Reorder or add lines here to change what the auto does; the states themselves never change.
     */
    protected abstract List<State> buildStates();

    /**
     * {@code final}: overriding this without calling {@code super} leaves {@code machine} null and
     * the OpMode dies of an NPE on its first loop, after START, where a team is least able to
     * diagnose it. {@link #onRouteInited()} is the supported place to add init-phase work.
     */
    @Override
    protected final void onSafeInit() {
        machine = new StateMachine(buildStates());
        onRouteInited();
    }

    /**
     * Enters the first state, on START rather than on INIT.
     *
     * <p>Starting here is what keeps a state's {@code init()} from running while the Driver
     * Station still shows INIT, where the driver may sit for an unbounded time before pressing
     * START: anything a state anchors in {@code init()} would be measuring against a period in
     * which the OpMode is not running at all.
     */
    @Override
    protected final void onSafeStart() {
        machine.start();
        onRouteStarted();
    }

    /**
     * {@code final} for the same reason as {@link #onSafeInit()}: the machine has to be stopped
     * or the active state never releases its hardware.
     */
    @Override
    protected final void onSafeStop() {
        if (machine != null) {
            machine.stop();
        }
    }

    /**
     * Runs once during the OpMode's INIT phase, after the route has been built.
     *
     * <p>Synapse's hardware map is ready by now, so this is where to look up devices. It runs
     * <em>after</em> {@link #buildStates()} because a team cannot hand a state a value the state
     * was already built without -- anything needed during construction has to be resolved inside
     * {@code buildStates()} itself.
     *
     * <p>Does nothing by default. Note that no state has been entered yet, so
     * {@link StateMachine#currentState()} is still {@code null}.
     */
    protected void onRouteInited() {}

    /**
     * Runs once when the driver presses START, after the first state has been entered.
     *
     * <p>After {@code machine.start()}, not before: a state is already initialised and active, so
     * {@link StateMachine#currentState()} is meaningful here and any hardware it holds is already
     * configured.
     *
     * <p>Does nothing by default.
     */
    protected void onRouteStarted() {}

    /**
     * Overridable on purpose: extra per-iteration work is a reasonable thing to want. Call
     * {@code super.onSafeLoop()} to keep the route advancing and the Driver Station updated.
     */
    @Override
    protected void onSafeLoop() {
        // Before the update, while the active state is still the current one.
        noteAnyStateMissingAnEndCondition();

        machine.update();
        reportTelemetry();

        if (shouldEndTheOpMode()) {
            endTheRoutine();
        }
    }

    /**
     * Whether the route has run out of states, and the OpMode should therefore ask the Robot
     * Controller to end it.
     *
     * <p>Package private and free of SDK calls on purpose. The decision is the part worth
     * testing -- "stops once, and not before" -- and the SDK call that acts on it is a single
     * line that cannot be exercised on a desktop JVM, because
     * {@link #requestOpModeStop()} is {@code final} in the real SDK and reaches straight into
     * Robot Controller internals. Separating them means the logic is verified by
     * {@code StateMachineOpModeEndsItselfTest} and the wiring is pinned by
     * {@code SdkReferenceTest}.
     */
    boolean shouldEndTheOpMode() {
        return machine != null && machine.isFinished();
    }

    /**
     * Asks the Robot Controller to end this OpMode, because the route has run out
     * of states.
     *
     * <p><strong>Without this the OpMode never ends.</strong> The SDK drives an
     * OpMode as {@code while (!stopRequested) { loop(); sleep(1); }} and only calls
     * {@code stop()} once that loop is exited, so a routine that finishes its
     * last state but never asks to be stopped keeps looping: the Driver Station
     * shows the OpMode as still RUNNING with telemetry frozen on {@code DONE} until
     * the Robot Controller force-kills it at the match timer.
     *
     * <p>{@code requestOpModeStop()} rather than {@code terminateOpModeNow()},
     * because the former sets the flag the loop tests and therefore exits
     * <em>through</em> {@code stop()} -- which still runs {@link #onSafeStop()} and
     * the last state's own {@code stop()}. The latter throws
     * {@code OpModeManagerImpl.ForceStopException} and takes the force-stop path,
     * which is the right tool for "abort now" and the wrong one for "finished".
     *
     * <p>Safe to call more than once, and a no-op if the Robot Controller has
     * already asked to stop.
     */
    private void endTheRoutine() {
        requestOpModeStop();
    }

    /**
     * The running machine, for the rare OpMode that needs more than {@link #buildStates()}.
     *
     * @return the machine, or {@code null} before {@link #onSafeInit()} has run
     */
    protected StateMachine machine() {
        return machine;
    }

    /**
     * Flags the one failure the runner cannot detect on its own: a state that never called
     * {@code setEndCondition(...)}.
     *
     * <p>Such a state loops exactly once and the route moves on. That is the right default -- a
     * stalled auto scores nothing, and one that skips a step might still score something -- but on
     * its own it is silent, and the shape of it is exactly the "a state got skipped" bug this
     * library exists to design out. A {@code DriveState} whose {@code init()} forgot the call runs
     * for one iteration with its motors off and the routine races through the rest of the route
     * looking, on the Driver Station, like it worked.
     *
     * <p>Checked before {@code update()} for the reason given on
     * {@link #statesMissingAnEndCondition}, and with {@code instanceof} rather than by adding a
     * method to {@link State}, so that the framework's interface stays exactly four methods and a
     * team that implements {@code State} directly is unaffected.
     */
    private void noteAnyStateMissingAnEndCondition() {
        State current = machine.currentState();
        if (!(current instanceof AbstractState)) {
            return;
        }
        if (((AbstractState) current).isEndConditionDefaulted()
                && !statesMissingAnEndCondition.contains(current.name())) {
            statesMissingAnEndCondition.add(current.name());
        }
    }

    private void reportTelemetry() {
        State current = machine.currentState();
        if (current == null) {
            telemetry.addData("Auto", "DONE");
        } else {
            telemetry.addData("Auto", "%d/%d", machine.currentIndex() + 1, machine.size());
            telemetry.addData("Step", "%d/%d", machine.currentStepIndex() + 1, machine.stepCount());
            telemetry.addData("State", current.name());
            if (machine.depth() > 1) {
                telemetry.addData("Phase", phaseLabel(machine));
            }
            telemetry.addData("State time", "%.2f s", machine.currentStateElapsedSeconds());
        }
        if (!statesMissingAnEndCondition.isEmpty()) {
            // Reported for the rest of the run, not just while the offending state is current,
            // because it is over almost immediately.
            telemetry.addData("State problem",
                    "%s: no end condition, ran one loop",
                    String.join(", ", statesMissingAnEndCondition));
        }
        telemetry.addData("Auto time", "%.2f s", machine.elapsedSeconds());
        telemetry.update();
    }

    /**
     * The groups enclosing the running state, outermost first: "RightScissor > Drive".
     *
     * <p>Assembled with a {@link StringBuilder} rather than {@code String.join}, which is Android
     * API 26 and the FTC SDK declares {@code minSdkVersion=24}. See {@code NoAndroidApiLeakTest}.
     */
    private String phaseLabel(StateMachine machine) {
        List<State> path = machine.path();
        StringBuilder label = new StringBuilder();
        // The last element of the path is the running state itself, already on its own line.
        for (int i = 0; i < path.size() - 1; i++) {
            if (label.length() > 0) {
                label.append(" > ");
            }
            label.append(path.get(i).name());
        }
        return label.toString();
    }
}
