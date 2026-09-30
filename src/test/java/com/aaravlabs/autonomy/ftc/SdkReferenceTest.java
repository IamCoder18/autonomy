package com.aaravlabs.autonomy.ftc;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins every FTC SDK reference the compiled adapter makes against a list
 * transcribed from the real SDK.
 *
 * <p>Autonomy's robot-facing class compiles against the hand-written stubs in
 * {@code ftc-stub/}, not against the real {@code org.firstinspires.ftc}
 * artifacts, because those ship only as AARs. That trade buys a fast,
 * dependency-free, desktop-JVM build, and it costs one specific safety net: a
 * stub that declares something the real SDK lacks produces a jar that compiles,
 * passes every other test, and throws {@code NoSuchMethodError} on a robot.
 *
 * <p>That is not hypothetical. The first version of the {@code Telemetry} stub
 * declared {@code addData(String, Object, Object...)}. The SDK declares
 * {@code addData(String, String, Object...)} -- the format parameter is a
 * {@code String}. Every test passed and the published jar was broken.
 *
 * <p>So this test reads the {@code Methodref}s and {@code Fieldref}s out of the
 * compiled {@code StateMachineOpMode} and requires each to appear in
 * {@link #SDK_11_2_1}. That list was produced by running {@code javap} against
 * the real {@code RobotCore-11.2.1.aar}:
 *
 * <pre>{@code
 * javap -cp RobotCore-11.2.1.aar org.firstinspires.ftc.robotcore.external.Telemetry
 * javap -p -cp RobotCore-11.2.1.aar com.qualcomm.robotcore.eventloop.opmode.OpModeInternal
 * }</pre>
 *
 * <p><strong>Keep this list, but do not trust it alone.</strong> It is
 * hand-written, and a hand-written allowlist is checked against itself: the
 * entry typed from memory rather than transcribed from the AAR agrees with
 * nothing but the assumption behind it. That is not hypothetical -- 0.1.2
 * shipped because this list contained a hand-written {@code update:()V} when
 * the SDK declares {@code update:()Z}, and the test passed while the robot
 * threw {@code NoSuchMethodError}.
 *
 * <p>{@link FtcStubFidelityTest} is the real check. It resolves the real
 * RobotCore AAR and compares the stub's declared methods against it by
 * descriptor, with no hand-maintained list in the loop. This test still earns
 * its place for the opposite direction: it pins what the library actually
 * calls, so a dead entry cannot rot into an unchecked permission.
 */
class SdkReferenceTest {

    private static final String ADAPTER =
            "com/aaravlabs/autonomy/ftc/StateMachineOpMode.class";

    /**
     * Every SDK member {@code StateMachineOpMode} is allowed to reference,
     * transcribed from {@code org.firstinspires.ftc:RobotCore:11.2.1}.
     *
     * <p>Field references are listed with the owner javac emits, which is the
     * class the field is accessed through rather than the one that declares it.
     * That is not an inaccuracy: the JVM resolves a field by walking up the
     * superclass chain (JVMS 5.4.3.2), so a field declared on
     * {@code OpModeInternal} and accessed through a subclass resolves fine.
     * Methods do <em>not</em> get that treatment -- an overload mismatch is
     * unresolvable -- which is exactly why {@code addData} had to be right.
     */
    private static final Set<String> SDK_11_2_1 = new LinkedHashSet<>(java.util.List.of(
            // OpMode lifecycle. Declared on OpMode in SDK 11.2.1.
            "com/qualcomm/robotcore/eventloop/opmode/OpMode.init:()V",
            "com/qualcomm/robotcore/eventloop/opmode/OpMode.loop:()V",
            "com/qualcomm/robotcore/eventloop/opmode/OpMode.stop:()V",
            // SafeOpMode's hooks, overridden by the adapter. From Synapse, not the
            // SDK, so they are not listed here -- this test only pins SDK members.
            // Telemetry. Note addData(String, String, Object...) -- String, not
            // Object, for the format. This is the line that was wrong once.
            "org/firstinspires/ftc/robotcore/external/Telemetry.addData:"
                    + "(Ljava/lang/String;Ljava/lang/Object;)"
                    + "Lorg/firstinspires/ftc/robotcore/external/Telemetry$Item;",
            "org/firstinspires/ftc/robotcore/external/Telemetry.addData:"
                    + "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)"
                    + "Lorg/firstinspires/ftc/robotcore/external/Telemetry$Item;",
            "org/firstinspires/ftc/robotcore/external/Telemetry.update:()Z"
    ));

    @Test
    @DisplayName("references only SDK members that RobotCore 11.2.1 actually declares")
    void referencesOnlyRealSdkMembers() throws IOException {
        Set<String> emitted = ConstantPool.externalReferences(ADAPTER);

        assertTrue(!emitted.isEmpty(),
                "no SDK references found in " + ADAPTER + "; the scan would pass vacuously");

        Set<String> unknown = new TreeSet<>(emitted);
        unknown.removeAll(SDK_11_2_1);

        if (!unknown.isEmpty()) {
            fail("StateMachineOpMode references SDK members that RobotCore 11.2.1 does not"
                    + " declare. The stub in ftc-stub/ is more permissive than the real SDK, so"
                    + " this compiles here and throws NoSuchMethodError on a robot.\n"
                    + "  Unrecognised: " + unknown + "\n"
                    + "Fix the stub to match the real SDK -- do not add the reference to the"
                    + " allowlist unless javap against the real .aar confirms it exists.");
        }
    }

    @Test
    @DisplayName("uses both addData overloads, so both stay pinned")
    void exercisesBothAddDataOverloads() throws IOException {
        Set<String> emitted = ConstantPool.externalReferences(ADAPTER);

        long addDataCalls = emitted.stream()
                .filter(ref -> ref.startsWith("org/firstinspires/ftc/robotcore/external/Telemetry.addData:"))
                .count();

        assertEquals(2, addDataCalls,
                "StateMachineOpMode should call both addData overloads -- the plain one for"
                        + " fixed text, the varargs one for formatted values. If this count"
                        + " drops, the overload you stopped using is no longer covered by the"
                        + " allowlist and the SDK check above has quietly weakened.");
    }

    @Test
    @DisplayName("keeps the allowlist honest: every entry is actually referenced")
    void allowlistHasNoDeadEntries() {
        // An allowlist accumulates entries that nothing references any more, and
        // a stale entry is a hole: it would let a future bad call through. This
        // keeps the list exactly as tight as the code needs.
        Set<String> emitted;
        try {
            emitted = ConstantPool.externalReferences(ADAPTER);
        } catch (IOException e) {
            throw new IllegalStateException("could not read the compiled adapter", e);
        }

        // init/loop/stop are invoked virtually on `this` and on SafeOpMode, so
        // they may or may not produce a Methodref depending on javac's choices.
        // Everything else must be live.
        Set<String> possiblyUnreferenced = new LinkedHashSet<>(java.util.List.of(
                "com/qualcomm/robotcore/eventloop/opmode/OpMode.init:()V",
                "com/qualcomm/robotcore/eventloop/opmode/OpMode.loop:()V",
                "com/qualcomm/robotcore/eventloop/opmode/OpMode.stop:()V"));

        Set<String> dead = new TreeSet<>(SDK_11_2_1);
        dead.removeAll(emitted);
        dead.removeAll(possiblyUnreferenced);

        assertTrue(dead.isEmpty(),
                "these allowlist entries are no longer referenced by the adapter, so they are"
                        + " no longer checked against anything. Remove them, or the allowlist"
                        + " rots into a list of permissions nobody verifies: " + dead);
    }
}
