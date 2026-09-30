package com.aaravlabs.autonomy.ftc;

import com.aaravlabs.synapse.ftc.SafeOpMode;
import com.aaravlabs.autonomy.State;
import com.aaravlabs.autonomy.StateMachine;
import java.util.List;

/**
 * Base OpMode for autonomous routines that are a sequence of {@link State}s.
 *
 * <p>A subclass does one thing: return its route from {@link #buildStates()}. Everything else --
 * running the sequence, calling the active state's {@code stop()} when the OpMode is interrupted,
 * and reporting progress to the Driver Station -- happens here.
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
     * Builds the route for this routine, in the order it should run.
     *
     * <p>Called once, after Synapse's hardware map is ready, so states can be handed real devices.
     * Reorder or add lines here to change what the auto does; the states themselves never change.
     */
    protected abstract List<State> buildStates();

    @Override
    protected void onSafeInit() {
        machine = new StateMachine(buildStates());
        machine.start();
    }

    @Override
    protected void onSafeLoop() {
        machine.update();
        reportTelemetry();
    }

    @Override
    protected void onSafeStop() {
        if (machine != null) {
            machine.stop();
        }
    }

    /**
     * The running machine, for the rare OpMode that needs more than {@link #buildStates()}.
     *
     * @return the machine, or {@code null} before {@link #onSafeInit()} has run
     */
    protected StateMachine machine() {
        return machine;
    }

    private void reportTelemetry() {
        State current = machine.currentState();
        if (current == null) {
            telemetry.addData("Auto", "DONE");
        } else {
            telemetry.addData("Auto", "%d/%d", machine.currentIndex() + 1, machine.size());
            telemetry.addData("State", current.name());
            telemetry.addData("State time", "%.2f s", machine.currentStateElapsedSeconds());
        }
        telemetry.addData("Auto time", "%.2f s", machine.elapsedSeconds());
        telemetry.update();
    }
}
