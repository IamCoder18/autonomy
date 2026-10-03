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
 * <p>Only the members Autonomy's adapter actually touches are declared, plus
 * {@link OpModeInternal#requestOpModeStop()} -- the one inherited member the
 * adapter calls to end a finished routine. {@code terminateOpModeNow()},
 * {@code updateTelemetry(Telemetry)}, and the rest of the real surface are
 * omitted because nothing calls them.
 *
 * <p><strong>An earlier version of this comment argued that omitting an unused
 * method "cannot change a call site that does not exist", and that was used to
 * justify leaving out {@code requestOpModeStop}.</strong> The reasoning is sound
 * per member and wrong in aggregate: the omission is invisible precisely because
 * the call site is also absent, so the stub and the adapter quietly agreed on a
 * StateMachine that runs to the end of its route and then keeps looping forever.
 * The stub is only faithful for members someone has thought about, which is why
 * {@code FtcStubFidelityTest} now checks every stub class in this package against
 * the real AAR rather than the three the adapter happens to reference.
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
