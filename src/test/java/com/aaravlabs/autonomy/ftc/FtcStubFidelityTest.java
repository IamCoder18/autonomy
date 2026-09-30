package com.aaravlabs.autonomy.ftc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * allowlist is a hand-written constant, and the entry that gets typed from
 * memory rather than transcribed from the AAR is the one that ships the bug --
 * which is exactly what happened with {@code update()V}. Checking the compiled
 * output against my own list only proves the list agrees with itself.
 *
 * <p>So the truth comes from the AAR. The build resolves
 * {@code org.firstinspires.ftc:RobotCore} and extracts its {@code classes.jar};
 * this test reads those bytes and compares the stub's declared methods against
 * the real ones, by descriptor. No hand-maintained list is involved.
 *
 * <p>The real SDK is not on the test classpath, deliberately: it declares the
 * same types as the stub, and both on one classpath is a duplicate-class
 * conflict that would make this test compare the stub against itself.
 */
class FtcStubFidelityTest {

    private static final Map<String, Set<String>> REAL_SDK_CACHE = new HashMap<>();

    @BeforeAll
    static void readTheRealSdk() throws IOException {
        String jar = System.getProperty("autonomy.realFtcSdk");
        assertTrue(jar != null && Files.isReadable(Paths.get(jar)),
                "the real SDK classes.jar was not extracted; build.gradle's extractRealFtcSdk"
                        + " task should have produced it and wired it in as a system property");
    }

    @Test
    @DisplayName("Telemetry.update() returns boolean, matching the SDK")
    void telemetryUpdateReturnsBoolean() throws IOException {
        // The regression, pinned on its own so the failure names itself rather
        // than arriving inside a set-difference assertion.
        assertTrue(realSdkHas("org/firstinspires/ftc/robotcore/external/Telemetry", "update:()Z"),
                "expected the real SDK to declare Telemetry.update()Z, but it does not. The stub"
                        + " and the test both need revisiting if the SDK has changed.");
        assertFalse(stubHas("org.firstinspires.ftc.robotcore.external.Telemetry", "update:()V"),
                "the Telemetry stub declares void update(), but the SDK returns boolean. The"
                        + " published jar would call update()V and throw NoSuchMethodError on a"
                        + " Robot Controller.");
    }

    @Test
    @DisplayName("every method the stub declares exists in the real SDK with the same descriptor")
    void stubDeclaresNothingTheSdkDoesNot() throws IOException {
        for (String internalName : new String[] {
                "org/firstinspires/ftc/robotcore/external/Telemetry",
                "com/qualcomm/robotcore/eventloop/opmode/OpMode",
                "com/qualcomm/robotcore/eventloop/opmode/OpModeInternal",
        }) {
            Set<String> stubbed = stubDescriptors(internalName);
            Set<String> real = realSdkMethods(internalName);

            assertFalse(stubbed.isEmpty(), "no stub methods read for " + internalName
                    + "; the test would pass vacuously");
            assertFalse(real.isEmpty(), "no methods read from the real SDK for " + internalName
                    + " -- has RobotCore changed packaging?");

            Set<String> wrong = new TreeSet<>(stubbed);
            wrong.removeAll(real);

            if (!wrong.isEmpty()) {
                fail("the stub declares members the real SDK does not, so the library compiles"
                        + " against a fiction and fails to link on a Robot Controller.\n"
                        + "  class:  " + internalName + "\n"
                        + "  wrong:  " + wrong + "\n"
                        + "Fix the stub from the real AAR, not from memory. The authoritative"
                        + " listing is `javap -cp <RobotCore>.aar!/classes.jar " + internalName
                        + "`, and what the JVM matches is the descriptor, not the Java"
                        + " signature -- `void f()` and `boolean f()` are the same call to a"
                        + " reader and different calls to the linker.");
            }
        }
    }

    @Test
    @DisplayName("the SDK version the stub is verified against is recorded in the build")
    void theVerifiedSdkVersionIsRecorded() {
        String version = System.getProperty("autonomy.ftcSdkVersion");
        assertTrue(version != null && !version.isEmpty(),
                "autonomy.ftcSdkVersion should be wired through from build.gradle so a failure"
                        + " names the SDK it was checked against");
    }

    // ---- helpers ---------------------------------------------------------------

    /** Descriptors the stub declares, by reading its own compiled class file. */
    private static Set<String> stubDescriptors(String internalName) throws IOException {
        String path = internalName.replace('.', '/') + ".class";
        // The stub is compiled into the compileOnly jar, not into main, so it is
        // read from there rather than from the test classpath.
        Path jar = Paths.get("build", "ftc-stub", "ftc-sdk-stub.jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                throw new IOException("the stub jar has no " + path);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return ConstantPool.declaredMethods(in.readAllBytes());
            }
        }
    }

    /** Descriptors the real SDK declares, read from the extracted classes.jar. */
    private static Set<String> realSdkMethods(String internalName) throws IOException {
        Set<String> cached = REAL_SDK_CACHE.get(internalName);
        if (cached != null) {
            return cached;
        }
        Path jar = Paths.get(System.getProperty("autonomy.realFtcSdk"));
        Set<String> methods = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    methods = ConstantPool.declaredMethods(in.readAllBytes());
                }
            }
        }
        REAL_SDK_CACHE.put(internalName, methods);
        return methods;
    }

    private static boolean realSdkHas(String internalName, String nameAndDescriptor)
            throws IOException {
        return realSdkMethods(internalName).contains(nameAndDescriptor);
    }

    private static boolean stubHas(String binaryName, String nameAndDescriptor)
            throws IOException {
        return stubDescriptors(binaryName.replace('.', '/')).contains(nameAndDescriptor);
    }
}
