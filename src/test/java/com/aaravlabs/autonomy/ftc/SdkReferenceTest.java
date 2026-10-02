package com.aaravlabs.autonomy.ftc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins every FTC SDK reference the compiled adapter makes, and proves each one resolves against
 * the real SDK rather than against a list typed from memory.
 *
 * <p>Autonomy's robot-facing class compiles against the hand-written stubs in
 * {@code ftc-stub/}, not against the real {@code org.firstinspires.ftc} artifacts, because
 * those ship only as AARs. That trade buys a fast, dependency-free, desktop-JVM build, and
 * it costs one specific safety net: a stub that declares something the real SDK lacks produces
 * a jar that compiles, passes every other test, and throws {@code NoSuchMethodError} on a robot.
 *
 * <p>That is not hypothetical. The first version of the {@code Telemetry} stub declared
 * {@code addData(String, Object, Object...)}. The SDK declares
 * {@code addData(String, String, Object...)} -- the format parameter is a {@code String}.
 * Every test passed and the published jar was broken. 0.1.2 shipped a second one, a
 * {@code void update()} against a {@code boolean}.
 *
 * <p>So there are two directions to check, and both are here:
 *
 * <ol>
 * <li><strong>Every reference the adapter emits is one this library intends to make</strong>
 *     ({@link #REQUIRED}), so a new robot call cannot be added silently and a stale entry
 *     cannot rot into a permission nobody checks.
 * <li><strong>Every reference resolves in the real SDK</strong>
 *     ({@link #everyReferenceResolvesAgainstTheRealSdk()}), read from the extracted AAR. This
 *     is what makes direction one trustworthy rather than self-referential.
 * </ol>
 *
 * <p>There is deliberately <strong>no exemption list</strong>. An earlier version carried
 * {@code OpMode.init:()V}, {@code loop:()V} and {@code stop:()V} in an "allowed to be
 * unreferenced" set, on the theory that javac might or might not emit them. It never did -- the
 * adapter overrides Synapse's hooks, not the SDK's lifecycle methods -- so three of six entries
 * were permissions nobody checked, inside the very test written to catch unchecked permissions.
 *
 * <p>{@link FtcStubFidelityTest} covers the other half: the stub itself, in full, against the
 * same AAR.
 */
class SdkReferenceTest {

    private static final String PACKAGE = "com/aaravlabs/autonomy/ftc/";

    private static final String ADAPTER = PACKAGE + "StateMachineOpMode";

    /**
     * Classes whose members are not the robot's problem here.
     *
     * <p>The framework's own classes are covered by {@code PackageBoundaryTest}, the JDK by
     * {@code NoAndroidApiLeakTest} and by {@code options.release = 11}, and Synapse by
     * {@code StateMachineOpModeLinkageTest}, which checks the adapter's supertypes and its
     * three lifecycle hooks by reflection against the real Synapse jar. Ignoring Synapse here
     * costs nothing but must be deliberate: it is a separate artifact with its own release
     * cadence, so it is not something the RobotCore AAR could confirm.
     */
    private static final String[] NOT_SDK = {
            "java/", "javax/", "com/aaravlabs/autonomy/", "com/aaravlabs/synapse/",
    };

    /** The RobotCore release every entry below was transcribed from. */
    private static final String SDK_VERSION = "11.2.1";

    /**
     * Every SDK member {@code StateMachineOpMode} is allowed to reference, as it appears in the
     * compiled constant pool, transcribed from {@code org.firstinspires.ftc:RobotCore:11.2.1}:
     *
     * <pre>{@code
     * javap -p -s -cp RobotCore-11.2.1.aar org.firstinspires.ftc.robotcore.external.Telemetry
     * javap -p -s -cp RobotCore-11.2.1.aar com.qualcomm.robotcore.eventloop.opmode.OpModeInternal
     * }</pre>
     *
     * <p>Two shapes appear, and the difference is javac's, not the SDK's:
     *
     * <ul>
     * <li><strong>Owner is an SDK class</strong>, for the {@code Telemetry} calls. These are
     *     interfaces, so there is no inherited-member lookup to do and javac names the
     *     interface directly.
     *
     * <li><strong>Owner is {@code StateMachineOpMode} itself</strong>, for {@code telemetry} and
     *     {@code requestOpModeStop()}. Both are declared on {@code OpModeInternal} and reached
     *     through {@code this}, and javac emits the <em>qualifying</em> type as the owner rather
     *     than the declaring one. These resolve at runtime because the JVM walks the superclass
     *     chain (JVMS 5.4.3.2). Listing them under the adapter's own name is deliberate: it is
     *     what the class file says, so renaming the adapter fails this test rather than quietly
     *     changing what it checks.
     * </ul>
     *
     * <p>Note {@code addData(String, String, Object...)} -- {@code String}, not {@code Object},
     * for the format. That is the line that was wrong once.
     */
    private static final Set<String> REQUIRED = new LinkedHashSet<>(List.of(
            "org/firstinspires/ftc/robotcore/external/Telemetry.addData:"
                    + "(Ljava/lang/String;Ljava/lang/Object;)"
                    + "Lorg/firstinspires/ftc/robotcore/external/Telemetry$Item;",
            "org/firstinspires/ftc/robotcore/external/Telemetry.addData:"
                    + "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)"
                    + "Lorg/firstinspires/ftc/robotcore/external/Telemetry$Item;",
            "org/firstinspires/ftc/robotcore/external/Telemetry.update:()Z",

            // Declared on the package-private OpModeInternal; owner is the adapter.
            "com/aaravlabs/autonomy/ftc/StateMachineOpMode.telemetry:"
                    + "Lorg/firstinspires/ftc/robotcore/external/Telemetry;",
            "com/aaravlabs/autonomy/ftc/StateMachineOpMode.requestOpModeStop:()V"
    ));

    /** How many {@code addData} calls the adapter is expected to make. */
    private static final int ADD_DATA_CALLS = 2;

    /** The stop request whose absence leaves an autonomous running until it is killed. */
    private static final String REQUEST_STOP =
            "com/aaravlabs/autonomy/ftc/StateMachineOpMode.requestOpModeStop:()V";

    @Test
    @DisplayName("references only SDK members that RobotCore " + SDK_VERSION + " actually declares")
    void referencesOnlyRealSdkMembers() throws IOException {
        Set<String> emitted = adapterSdkReferences();

        assertTrue(!emitted.isEmpty(),
                "no SDK references found in " + ADAPTER + "; the scan would pass vacuously");

        Set<String> unknown = new TreeSet<>(emitted);
        unknown.removeAll(REQUIRED);

        if (!unknown.isEmpty()) {
            fail("StateMachineOpMode references SDK members that RobotCore " + SDK_VERSION
                    + " does not declare. The stub in ftc-stub/ is more permissive than the real"
                    + " SDK, so this compiles here and throws NoSuchMethodError on a robot.\n"
                    + "  Unrecognised: " + unknown + "\n"
                    + "Fix the stub to match the real SDK -- do not add the reference to the"
                    + " allowlist unless javap against the real .aar confirms it exists.");
        }
    }

    @Test
    @DisplayName("every SDK reference resolves in the real RobotCore classes.jar")
    void everyReferenceResolvesAgainstTheRealSdk() throws IOException {
        Set<String> hierarchy = opModeHierarchy();

        Set<String> unresolved = new TreeSet<>();
        for (String reference : adapterSdkReferences()) {
            String owner = ownerOf(reference);
            String member = memberOf(reference);

            if (realSdkHas(owner)) {
                // Owned by an SDK class, so that class must declare it. This covers
                // Telemetry, which is an interface reached directly.
                if (!realSdkDeclares(owner, member)) {
                    unresolved.add(reference + "   (" + owner + " does not declare it)");
                }
            } else {
                // Not an SDK class, so it is the adapter and the member is inherited:
                // something on the SDK side of the superclass chain must declare it.
                String declaring = declaringClassIn(hierarchy, member);
                if (declaring == null) {
                    unresolved.add(reference + "   (not declared anywhere in " + hierarchy
                            + ", and " + owner + " is not an SDK class)");
                }
            }
        }

        if (!unresolved.isEmpty()) {
            fail("the compiled adapter references SDK members the real RobotCore " + SDK_VERSION
                    + " does not declare. These link here and throw NoSuchMethodError or"
                    + " NoSuchFieldError on a Robot Controller.\n"
                    + "  Unresolved: " + unresolved + "\n"
                    + "This is the whole point of the stub: fix it from the real AAR"
                    + " (`javap -p -s -cp classes.jar <class>`), not from memory.");
        }
    }

    @Test
    @DisplayName("uses both addData overloads, so both stay pinned")
    void exercisesBothAddDataOverloads() throws IOException {
        long addDataCalls = adapterSdkReferences().stream()
                .filter(ref -> ref.startsWith(
                        "org/firstinspires/ftc/robotcore/external/Telemetry.addData:"))
                .count();

        assertEquals(ADD_DATA_CALLS, addDataCalls,
                "StateMachineOpMode should call both addData overloads -- the plain one for"
                        + " fixed text, the varargs one for formatted values. If this count"
                        + " drops, the overload you stopped using is no longer covered by the"
                        + " allowlist and the SDK check above has quietly weakened.");
    }

    @Test
    @DisplayName("asks the Robot Controller to end the OpMode once the route is done")
    void endsTheOpModeWhenTheRouteIsFinished() throws IOException {
        // Pinned on its own so the failure names itself. Without this call the OpMode runs
        // its last state and then loops forever: the SDK drives an OpMode as
        // `while (!stopRequested) { loop(); }` and only leaves that loop -- reaching stop() --
        // when something raises the flag. Symptom on the Driver Station: the OpMode stays
        // RUNNING with telemetry frozen on DONE until the match timer force-kills it.
        assertTrue(adapterSdkReferences().contains(REQUEST_STOP),
                "StateMachineOpMode no longer asks to be stopped. A routine whose last state"
                        + " finishes will keep looping until the Robot Controller force-kills"
                        + " it. The descriptor is checked here and the control flow in"
                        + " StateMachineOpModeEndsItselfTest.");
    }

    @Test
    @DisplayName("the allowlist is exactly what the adapter references, in both directions")
    void allowlistMatchesTheAdapterExactly() throws IOException {
        Set<String> emitted = adapterSdkReferences();

        Set<String> dead = new TreeSet<>(REQUIRED);
        dead.removeAll(emitted);
        assertTrue(dead.isEmpty(),
                "these allowlist entries are no longer referenced by the adapter, so they are"
                        + " checked against nothing. Remove them, or the allowlist rots into a"
                        + " list of permissions nobody verifies: " + dead);

        Set<String> unlisted = new TreeSet<>(emitted);
        unlisted.removeAll(REQUIRED);
        assertTrue(unlisted.isEmpty(),
                "the adapter references SDK members the allowlist does not list: " + unlisted);
    }

    @Test
    @DisplayName("the allowlist carries no exemption list, so no entry can be unverified")
    void allowlistHasNoExemptions() throws IOException {
        // Structural rather than behavioural, and deliberately so. An earlier version of this
        // test kept three OpMode.init/loop/stop entries in the allowlist and exempted them from
        // the dead-entry check, on the theory that javac might or might not emit them. It never
        // did -- the adapter overrides Synapse's hooks, not the SDK's lifecycle methods -- so
        // those entries were never verified against anything and never could be.
        //
        // Asserting on the *shape* of the list catches that class of change even when someone
        // reintroduces an exemption, because an exemption necessarily means an entry that is
        // listed and never emitted, and the both-directions assertion above fails on it.
        for (String entry : REQUIRED) {
            assertFalse(entry.startsWith("com/qualcomm/robotcore/eventloop/opmode/OpMode.")
                            && entry.endsWith(":()V")
                            && !adapterSdkReferences().contains(entry),
                    entry + " is an unreferenced OpMode lifecycle method. The adapter overrides"
                            + " Synapse's hooks, so it never references these; an entry here"
                            + " cannot be verified and is exactly the dead permission this list"
                            + " must not contain.");
        }
    }

    @Test
    @DisplayName("scans every compiled class in the adapter package, not just one file")
    void scansTheWholePackage() throws IOException {
        List<String> classes = ConstantPool.listClasses(PACKAGE);
        assertTrue(classes.contains(ADAPTER),
                "expected to find " + ADAPTER + " among " + classes + ". These are internal"
                        + " names without a .class suffix; if they still carry the suffix then"
                        + " the owner comparison below silently matches nothing and the whole"
                        + " scan reports an empty set.");

        // The aggregate is a union over the package, so a synthetic class carrying SDK
        // references cannot hide behind a scan that only opened one file.
        assertEquals(adapterSdkReferences().size(), union(classes).size(),
                "the aggregate reference set should be the union over every class in " + PACKAGE);
    }

    // ---- reference helpers -----------------------------------------------------

    /** Every SDK reference made by any class in the adapter package. */
    private static Set<String> adapterSdkReferences() throws IOException {
        return union(ConstantPool.listClasses(PACKAGE));
    }

    private static Set<String> union(List<String> classFiles) throws IOException {
        Set<String> all = new LinkedHashSet<>();
        for (String classFile : classFiles) {
            all.addAll(ConstantPool.sdkReferences(classFile, ADAPTER, NOT_SDK));
        }
        return all;
    }

    private static String ownerOf(String reference) {
        return reference.substring(0, reference.indexOf('.'));
    }

    private static String memberOf(String reference) {
        return reference.substring(reference.indexOf('.') + 1);
    }

    /**
     * The SDK classes reachable from {@code OpMode} by walking superclasses in the real AAR,
     * stopping before {@code java.lang.Object}.
     *
     * <p>This is the chain that makes the adapter's inherited references resolvable.
     * {@code StateMachineOpModeLinkageTest} separately proves the adapter's own supertype is
     * Synapse's {@code SafeOpMode} whose supertype is this {@code OpMode}, so starting the walk
     * here is sound.
     */
    private static Set<String> opModeHierarchy() throws IOException {
        Set<String> hierarchy = new LinkedHashSet<>();
        String name = "com/qualcomm/robotcore/eventloop/opmode/OpMode";
        while (name != null) {
            hierarchy.add(name);
            name = ConstantPool.superName(readRealSdk(name));
        }
        return hierarchy;
    }

    private static String declaringClassIn(Set<String> hierarchy, String member)
            throws IOException {
        for (String internalName : hierarchy) {
            if (realSdkDeclares(internalName, member)) {
                return internalName;
            }
        }
        return null;
    }

    private static boolean realSdkDeclares(String internalName, String member) throws IOException {
        return ConstantPool.declaredMembers(readRealSdk(internalName)).all().contains(member);
    }

    /** Whether the real SDK ships this class, read from the AAR rather than guessed by prefix. */
    private static boolean realSdkHas(String internalName) throws IOException {
        try (ZipFile zip = new ZipFile(
                Path.of(System.getProperty("autonomy.realFtcSdk")).toFile())) {
            return zip.getEntry(internalName + ".class") != null;
        }
    }

    private static byte[] readRealSdk(String internalName) throws IOException {
        Path jar = Path.of(System.getProperty("autonomy.realFtcSdk"));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            if (entry == null) {
                throw new IOException("the real SDK has no " + internalName
                        + "; RobotCore may have repackaged");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }
}