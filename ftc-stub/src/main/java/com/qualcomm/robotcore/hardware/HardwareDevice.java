package com.qualcomm.robotcore.hardware;

/**
 * Compile-time stub of the FTC SDK's {@code HardwareDevice}.
 *
 * <p>Declared only because {@link HardwareMap#get(String)} returns it, and
 * javac has to resolve a method's return type to compile a call site at all.
 *
 * <p><strong>Intentionally memberless.</strong> Autonomy never calls anything on
 * a {@code HardwareDevice} -- it gets devices out of Synapse's
 * {@code SafeDevice} wrappers, not by name -- and a stub only has to be a
 * <em>subset</em> of the real API to be safe. An empty interface can never be
 * more permissive than the real one, whereas transcribing six methods nobody
 * calls would be six more chances to get a descriptor wrong. See
 * {@link com.qualcomm.robotcore.eventloop.opmode.OpMode} for the rule.
 */
public interface HardwareDevice {
}