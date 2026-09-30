package org.firstinspires.ftc.robotcore.external;

/**
 * Compile-time stub of the FTC SDK's {@code Telemetry}.
 *
 * <p>See {@link com.qualcomm.robotcore.eventloop.opmode.OpMode} for why this
 * exists and why signatures must match the real SDK byte for byte.
 *
 * <p>Both {@code addData} overloads below are transcribed from SDK 11.2.1,
 * where the interface declares:
 *
 * <pre>{@code
 * Item addData(String cap, Object format);
 * Item addData(String cap, String format, Object... args);
 * }</pre>
 *
 * <p>The varargs form takes {@code String}, <strong>not</strong> {@code Object},
 * for its format. Inventing an {@code addData(String, Object, Object...)} looks
 * harmless and is not: the call site compiles, and the published jar then carries
 * a {@code Methodref} for an overload the SDK does not have, which surfaces as
 * {@code NoSuchMethodError} on the robot. {@code StateMachineOpMode} calls both
 * forms, so both must be declared.
 *
 * <p>The {@code Func}-taking overloads are omitted: nothing in Autonomy uses
 * them, and an unused omission cannot change a call site that does not exist.
 */
public interface Telemetry {

    /**
     * Adds a line whose value is {@code format} verbatim.
     *
     * @param cap    caption shown in the Driver Station's left column
     * @param format the value
     */
    Item addData(String cap, Object format);

    /**
     * Adds a line whose value is {@link String#format} applied to {@code args}.
     *
     * @param cap    caption shown in the Driver Station's left column
     * @param format a {@link String#format} pattern
     * @param args   arguments for {@code format}
     */
    Item addData(String cap, String format, Object... args);

    /** Flushes the accumulated lines to the Driver Station. */
    void update();

    /** A single telemetry line, for further configuration before the next update. */
    interface Item {

        Item setCaption(String caption);

        Item setValue(String value, Object... args);

        Item setValue(Object value);

        Item setRetained(Boolean retained);
    }
}
