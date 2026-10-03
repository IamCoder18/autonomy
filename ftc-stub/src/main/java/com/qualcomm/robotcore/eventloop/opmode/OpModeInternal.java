package com.qualcomm.robotcore.eventloop.opmode;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;
import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Compile-time stub of the FTC SDK's {@code OpModeInternal}.
 *
 * <p>This class exists for one reason, and getting it wrong is invisible until
 * a robot throws {@code NoSuchFieldError}: in SDK 11.2 {@link OpMode} does
 * <em>not</em> declare {@code telemetry}, {@code hardwareMap}, or the gamepads.
 * It extends this package-private superclass, and that is where they live.
 *
 * <p>A field access compiles to a {@code getfield} naming the class that
 * <em>declares</em> the field. Declare these on {@code OpMode} in the stub
 * instead, and javac emits {@code getfield OpMode.telemetry} -- a field the real
 * SDK does not have. The library would compile, pass every test, and fail on the
 * field with {@code NoSuchFieldError} the first time an OpMode reported
 * telemetry.
 *
 * <p>Package private, matching the real SDK. {@code OpMode} is public and
 * extends it, which is legal; consumers in other packages reach the fields
 * through the public {@code OpMode} type.
 *
 * @see com.qualcomm.robotcore.eventloop.opmode.OpMode
 */
abstract class OpModeInternal {

    public volatile Gamepad gamepad1;
    public volatile Gamepad gamepad2;
    public Telemetry telemetry;
    public volatile HardwareMap hardwareMap;

    /**
     * Asks the Robot Controller to end this OpMode: the next iteration of
     * {@code internalRunOpMode} sees the flag and falls out of its loop into
     * {@code OpMode.stop()}.
     *
     * <p><strong>Declared here, on the package-private superclass, exactly as the
     * real SDK declares it.</strong> Autonomy calls this to end a routine that has
     * run out of states, and it is the graceful way to do it -- unlike
     * {@code OpMode.terminateOpModeNow()}, which throws
     * {@code OpModeManagerImpl.ForceStopException} and takes the force-stop path
     * instead.
     *
     * <p>A subclass in another package can call it even though this class is not
     * public: the member is inherited by the public {@link OpMode}, and both javac
     * and the JVM resolve an inherited public member through the accessible
     * subclass. {@code SdkReferenceTest} pins the resulting reference.
     */
    public final void requestOpModeStop() {
        throw new UnsupportedOperationException("stub");
    }
}
