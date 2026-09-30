package org.firstinspires.ftc.robotcore.external;

/**
 * Compile-time stub of the FTC SDK's {@code Telemetry}.
 *
 * <p>See {@link com.qualcomm.robotcore.eventloop.opmode.OpMode} for why this
 * exists and why the signatures must match the real SDK.
 *
 * <p>The varargs on {@link #addData(String, Object, Object...)} are what make
 * both {@code addData("Auto", "DONE")} and
 * {@code addData("State", "%d/%d", i, n)} legal, exactly as in the SDK. A
 * two-parameter stub would reject the formatted calls in
 * {@code StateMachineOpMode}.
 */
public interface Telemetry {

    /**
     * Adds a line to the next telemetry packet.
     *
     * @param cap   caption shown in the Driver Station's left column
     * @param format value, or a {@link String#format} pattern when args follow
     * @param args  arguments for {@code format}
     */
    Item addData(String cap, Object format, Object... args);

    /** Flushes the accumulated lines to the Driver Station. */
    void update();

    /** A single telemetry line, for further configuration before the next update. */
    interface Item {

        Item setCaption(String caption);

        Item setValue(String value);

        Item setValueFormat(String format);

        Item setRetained(Boolean retained);
    }
}
