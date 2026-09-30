package com.qualcomm.robotcore.eventloop.opmode;

/**
 * Compile-time stub of the FTC SDK's {@code OpMode}.
 *
 * <p><strong>This is not the real SDK.</strong> It exists so
 * {@code StateMachineOpMode} can be compiled and its supertype chain resolved on
 * a plain desktop JVM. The Robot Controller app supplies the real
 * {@code com.qualcomm.robotcore.*} classes at runtime, so this stub is
 * {@code compileOnly} and is never published.
 *
 * <p>Signatures must match the real SDK exactly. A mismatch compiles cleanly and
 * then fails on a robot with {@code NoSuchMethodError} or
 * {@code NoSuchFieldError}, which is the worst possible time to find out. Keep
 * this file in step with the SDK version named in the README.
 *
 * <p>Note that {@code OpMode} extends the package-private
 * {@link OpModeInternal}, which is where the real SDK declares
 * {@code telemetry} and {@code hardwareMap}. Do not "simplify" by moving those
 * fields here; see the comment on {@code OpModeInternal}.
 *
 * <p>Only the members Autonomy's adapter actually touches are declared.
 * {@code terminateOpModeNow()}, {@code updateTelemetry(Telemetry)}, and the rest
 * of the real surface are omitted -- they are inherited or unused, and omitting
 * an unused method cannot change a call site that does not exist. Omitting a
 * method that <em>is</em> used, or declaring one with the wrong signature, can.
 */
public abstract class OpMode extends OpModeInternal {

    public volatile double time;

    public abstract void init();

    public void init_loop() {
    }

    public void start() {
    }

    public abstract void loop();

    public void stop() {
    }

    public double getRuntime() {
        return 0.0;
    }

    public void resetRuntime() {
    }
}
