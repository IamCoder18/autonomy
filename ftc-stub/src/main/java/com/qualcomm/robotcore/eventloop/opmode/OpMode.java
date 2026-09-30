package com.qualcomm.robotcore.eventloop.opmode;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;
import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Compile-time stub of the FTC SDK's {@code OpMode}.
 *
 * <p><strong>This is not the real SDK.</strong> It exists so {@code
 * StateMachineOpMode} can be compiled and its supertype chain resolved on a
 * plain desktop JVM. The Robot Controller app supplies the real
 * {@code com.qualcomm.robotcore.*} classes at runtime, so this stub is
 * {@code compileOnly} and is never published.
 *
 * <p>Signatures must match the real SDK exactly. A mismatch here compiles
 * cleanly and then fails with {@code NoSuchMethodError} on a robot, which is
 * the worst possible time to find out. Keep this file in step with the SDK
 * version named in {@code gradle.properties}.
 *
 * <p>Only the members Autonomy actually touches are declared. Synapse carries
 * its own, fuller copy of this stub; the two are independent and are never on
 * the same classpath.
 */
public abstract class OpMode {

    public HardwareMap hardwareMap = null;
    public Gamepad gamepad1 = null;
    public Gamepad gamepad2 = null;
    public Telemetry telemetry = null;
    public volatile double time;

    public abstract void init();

    public void init_loop() {
    }

    public abstract void loop();

    public void start() {
    }

    public void stop() {
    }

    public double getRuntime() {
        return 0.0;
    }

    public void resetRuntime() {
    }
}
