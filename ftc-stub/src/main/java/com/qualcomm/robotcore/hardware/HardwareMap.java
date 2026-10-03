package com.qualcomm.robotcore.hardware;

/**
 * Compile-time stub of the FTC SDK's {@code HardwareMap}.
 *
 * <p>See {@link com.qualcomm.robotcore.eventloop.opmode.OpMode} for why this
 * exists. Autonomy never calls these methods; the class is declared only
 * because {@code OpModeInternal} names it as a field type, and javac must be
 * able to resolve that type.
 *
 * <p>Both signatures are transcribed from RobotCore 11.2.1 and checked against
 * the real AAR by {@code FtcStubFidelityTest}:
 *
 * <pre>{@code
 * public <T> T get(Class<? extends T>, String);   // erases to (Class, String) -> Object
 * public HardwareDevice get(String);              // NOT -> Object
 * }</pre>
 *
 * <p>The second one used to be declared {@code <T> T get(String)}, which erases
 * to {@code (Ljava/lang/String;)Ljava/lang/Object;}. The SDK declares
 * {@code (Ljava/lang/String;)Lcom/qualcomm/robotcore/hardware/HardwareDevice;}.
 * A call compiled against the stub would have linked here and thrown
 * {@code NoSuchMethodError} on a Robot Controller. Nothing in Autonomy calls
 * it, which is exactly why it survived until the fidelity sweep was widened to
 * check every stub class rather than the three the adapter references.
 */
public class HardwareMap {

    public <T> T get(Class<? extends T> clazz, String name) {
        throw new UnsupportedOperationException("stub");
    }

    public HardwareDevice get(String name) {
        throw new UnsupportedOperationException("stub");
    }
}