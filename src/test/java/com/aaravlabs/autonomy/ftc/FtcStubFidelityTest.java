package com.aaravlabs.autonomy.ftc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the hand-written FTC stubs against the <em>real</em> SDK.
 *
 * <p>This test exists because 0.1.2 shipped a broken jar. The
 * {@code Telemetry} stub declared {@code void update()} where the SDK declares
 * {@code boolean update()}. Every call site compiled, the whole suite passed,
 * and the Robot Controller threw on the first telemetry flush:
 *
 * <pre>{@code
 * java.lang.NoSuchMethodError: No interface method update()V in class
 *   Lorg/firstinspires/ftc/robotcore/external/Telemetry;
 * }</pre>
 *
 * <p>{@link SdkReferenceTest} was supposed to prevent that and did not. Its
 * allowlist is a hand-written constant, and the entry typed from memory
 * rather than transcribed from the AAR was the one that shipped the bug --
 * the test was checking that an assumption agreed with itself.
 *
 * <p>So the truth comes from the AAR. The build resolves
 * {@code org.firstinspires.ftc:RobotCore} and extracts its {@code classes.jar};
 * this test reads those bytes and compares the stub against the real thing by
 * descriptor. No hand-maintained list is involved.
 *
 * <p><strong>Every class in the stub, and every member of each.</strong> The list
 * below used to be the three classes {@code StateMachineOpMode} happens to
 * reference, which left {@code Autonomous}, {@code TeleOp}, {@code Gamepad},
 * {@code HardwareMap} and {@code Telemetry.Item} unchecked -- and there was
 * already drift behind it: the stub's {@code @Autonomous} declared
 * {@code preselectTeleOp()} as {@code int} where the SDK declares {@link String},
 * plus three members ({@code groupAlt}, {@code preselectAutonomous},
 * {@code initOp}) that the real annotation does not have at all. Checking only
 * the classes the adapter touches is checking only the part someone already
 * looked at.
 *
 * <p>Fields are checked too, and for the same reason methods are: {@code telemetry}
 * and {@code hardwareMap} live on {@code OpModeInternal}, and a stub that declared
 * them on {@code OpMode} would compile and then fail with {@code NoSuchFieldError}.
 *
 * <p>The real SDK is not on the test classpath, deliberately: it declares the
 * same types as the stub, and both on one classpath is a duplicate-class
 * conflict that would make this test compare the stub against itself.
 */
class FtcStubFidelityTest {

    /** Every class the stub declares, by internal name. Discovered, not listed. */
    private static final Map<String, ConstantPool.Members> REAL_SDK_CACHE =
            new LinkedHashMap<>();

    private static final String STUB_JAR_PROPERTY = "autonomy.ftcStubJar";
    private static final String REAL_SDK_PROPERTY = "autonomy.realFtcSdk";

    @BeforeAll
    static void readTheRealSdk() throws IOException {
        for (String property : new String[] {STUB_JAR_PROPERTY, REAL_SDK_PROPERTY}) {
            String path = System.getProperty(property);
            assertTrue(path != null && Files.isReadable(Paths.get(path)),
                    property + " was not wired to a readable file. build.gradle should pass both"
                            + " as absolute paths, so this test does not depend on the working"
                            + " directory or on the build directory's name.");
        }
    }

    @Test
    @DisplayName("Telemetry.update() returns boolean, matching the SDK")
    void telemetryUpdateReturnsBoolean() throws IOException {
        // The regression, pinned on its own so the failure names itself rather
        // than arriving inside a set-difference assertion.
        assertTrue(realSdkMembers("org/firstinspires/ftc/robotcore/external/Telemetry")
                        .methods.contains("update:()Z"),
                "expected the real SDK to declare Telemetry.update()Z, but it does not. The stub"
                        + " and the test both need revisiting if the SDK has changed.");
        assertFalse(stubMembers("org/firstinspires/ftc/robotcore/external/Telemetry")
                        .methods.contains("update:()V"),
                "the Telemetry stub declares void update(), but the SDK returns boolean. The"
                        + " published jar would call update()V and throw NoSuchMethodError on a"
                        + " Robot Controller.");
    }

    @Test
    @DisplayName("@Autonomous's members match the SDK exactly")
    void autonomousAnnotationMatchesTheSdk() throws IOException {
        // Pinned separately from the sweep below because it is the worst drift the stub
        // ever carried: a wrong return *type* on an annotation member, plus three
        // members the real annotation does not declare.
        ConstantPool.Members stub = stubMembers(
                "com/qualcomm/robotcore/eventloop/opmode/Autonomous");
        ConstantPool.Members real = realSdkMembers(
                "com/qualcomm/robotcore/eventloop/opmode/Autonomous");

        assertEqualsMembers("Autonomous",
                withoutConstructors(stub.methods), withoutConstructors(real.methods));
    }

    @Test
    @DisplayName("@TeleOp's members match the SDK exactly")
    void teleOpAnnotationMatchesTheSdk() throws IOException {
        assertEqualsMembers("TeleOp",
                withoutConstructors(
                        stubMembers("com/qualcomm/robotcore/eventloop/opmode/TeleOp").methods),
                withoutConstructors(
                        realSdkMembers("com/qualcomm/robotcore/eventloop/opmode/TeleOp").methods));
    }

    @Test
    @DisplayName("every method and field the stub declares exists in the real SDK")
    void stubDeclaresNothingTheSdkDoesNot() throws IOException {
        List<String> stubClasses = stubClassNames();

        assertTrue(stubClasses.size() >= 9,
                "expected the whole stub package to be scanned, found only " + stubClasses
                        + ". A hand-written list of classes to check is how"
                        + " Autonomous/TeleOp drifted in the first place.");

        int compared = 0;
        for (String internalName : stubClasses) {
            ConstantPool.Members stub = stubMembers(internalName);
            ConstantPool.Members real = realSdkMembers(internalName);

            // realSdkMembers throws if the class is absent, so both sides were read. A stub
            // class with no members is legitimate and deliberate -- an empty interface is a
            // subset of any interface -- so the non-vacuity guarantee is made over the whole
            // sweep below rather than per class.
            compared += withoutConstructors(stub.methods).size() + stub.fields.size();

            reportWrong(internalName, "method",
                    withoutConstructors(stub.methods), withoutConstructors(real.methods));
            reportWrong(internalName, "field", stub.fields, real.fields);
        }

        // Non-vacuity, stated where it can actually hold: if the class-file parser quietly
        // stopped finding members, every per-class comparison above would pass on empty sets.
        assertTrue(compared >= 20,
                "only " + compared + " stub members were compared across " + stubClasses.size()
                        + " classes; expected the full stub surface. The sweep would be"
                        + " passing vacuously.");
    }

    @Test
    @DisplayName("the SDK version the stub is verified against is recorded in the build")
    void theVerifiedSdkVersionIsRecorded() {
        String version = System.getProperty("autonomy.ftcSdkVersion");
        assertTrue(version != null && !version.isEmpty(),
                "autonomy.ftcSdkVersion should be wired through from build.gradle so a failure"
                        + " names the SDK it was checked against");

        // A placeholder, a branch name, or a stray whitespace-only value would let the
        // suite pass while naming no SDK at all, which is the one thing this property is
        // for. Require something that looks like a released version.
        assertTrue(version.matches("\\d+\\.\\d+(\\.\\d+)?.*"),
                "autonomy.ftcSdkVersion should be a RobotCore version like 11.2.1, but was \""
                        + version + "\". This is the value a failure message quotes, so it has to"
                        + " actually identify the release the stub was checked against.");
    }

    // ---- assertions -------------------------------------------------------------

    private static void assertEqualsMembers(
            String what, Set<String> stubbed, Set<String> real) {
        Set<String> wrong = new TreeSet<>(stubbed);
        wrong.removeAll(real);
        if (!wrong.isEmpty()) {
            fail(what + ": the stub declares members the real SDK does not. A stub more permissive"
                    + " than the SDK compiles here and fails to link on a Robot Controller.\n"
                    + "  wrong:  " + wrong + "\n"
                    + "  real:   " + new TreeSet<>(real) + "\n"
                    + "Fix the stub from the real AAR, not from memory. What the JVM matches is"
                    + " the descriptor, not the Java signature: `void f()` and `boolean f()` look"
                    + " identical in source and are different calls to the linker, and so are"
                    + " `int f()` and `String f()`.");
        }
    }

    private static void reportWrong(
            String internalName, String kind, Set<String> stubbed, Set<String> real) {
        Set<String> wrong = new TreeSet<>(stubbed);
        wrong.removeAll(real);
        if (!wrong.isEmpty()) {
            fail("the stub declares " + kind + "s the real SDK does not, so the library compiles"
                    + " against a fiction and fails to link on a Robot Controller.\n"
                    + "  class:  " + internalName + "\n"
                    + "  wrong:  " + wrong + "\n"
                    + "Fix the stub from the real AAR, not from memory. The authoritative"
                    + " listing is `javap -p -s -cp <RobotCore>.aar!/classes.jar " + internalName
                    + "`, and what the JVM matches is the descriptor, not the Java signature.");
        }
    }

    // ---- helpers ---------------------------------------------------------------

    /** Every {@code .class} entry in the stub jar, by internal name. Discovered, not listed. */
    private static List<String> stubClassNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(stubJar().toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                String internalName = name.substring(0, name.length() - ".class".length());
                // package-info carries javadoc only: no members to drift, and no counterpart
                // in the AAR because it is not part of the published API.
                if (!internalName.endsWith("package-info")) {
                    names.add(internalName);
                }
            }
        }
        return names;
    }

    /**
     * Strips constructors before comparison.
     *
     * <p>A class compiled from source always carries a default constructor, while the real SDK
     * classes are instantiated by the Robot Controller and several have no no-arg constructor
     * at all -- {@code HardwareMap}'s takes a {@code Context} and an
     * {@code OpModeManagerNotifier}. Matching constructors would mean stubbing
     * {@code android.content.Context}, which would drag the Android SDK onto a compile
     * classpath that exists precisely to avoid it.
     *
     * <p>So constructors are out of scope, and it is worth being explicit that this is a real
     * gap rather than a non-issue: {@code new HardwareMap()} would compile against the stub and
     * fail on the robot. Nothing in Autonomy constructs any of these types -- the Robot
     * Controller constructs OpModes itself, and the two {@code OpMode} constructors that
     * <em>are</em> declared do match -- so the residual risk is confined to consumer test code
     * that constructs SDK types directly.
     */
    private static Set<String> withoutConstructors(Set<String> members) {
        Set<String> kept = new LinkedHashSet<>();
        for (String member : members) {
            if (!member.startsWith("<init>:")) {
                kept.add(member);
            }
        }
        return kept;
    }

    /** Members declared by the stub, read from the jar build.gradle compiled it into. */
    private static ConstantPool.Members stubMembers(String internalName) throws IOException {
        String path = internalName + ".class";
        try (ZipFile zip = new ZipFile(stubJar().toFile())) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                throw new IOException("the stub jar has no " + path);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return ConstantPool.declaredMembers(in.readAllBytes());
            }
        }
    }

    /** Members declared by the real SDK, read from the extracted classes.jar. */
    private static ConstantPool.Members realSdkMembers(String internalName) throws IOException {
        ConstantPool.Members cached = REAL_SDK_CACHE.get(internalName);
        if (cached != null) {
            return cached;
        }
        ConstantPool.Members members;
        try (ZipFile zip = new ZipFile(realSdkJar().toFile())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            if (entry == null) {
                throw new IOException("the real SDK has no " + internalName
                        + "; RobotCore may have repackaged. Every stub class must now correspond"
                        + " to a real one, so this is a hard failure rather than a skip.");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                members = ConstantPool.declaredMembers(in.readAllBytes());
            }
        }
        REAL_SDK_CACHE.put(internalName, members);
        return members;
    }

    private static Path stubJar() {
        return Paths.get(System.getProperty(STUB_JAR_PROPERTY));
    }

    private static Path realSdkJar() {
        return Paths.get(System.getProperty(REAL_SDK_PROPERTY));
    }
}