package com.aaravlabs.autonomy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the Android API level the library can actually run at.
 *
 * <p>The FTC SDK declares {@code minSdkVersion=24}, but several JDK classes a
 * state machine would reach for naturally are API 26. Using one on an API 24 or
 * 25 Robot Controller throws {@code NoClassDefFoundError} the first time the
 * state runs -- a failure no desktop test would ever catch, and a bad one to
 * meet on a competition day.
 *
 * <p>So the constraint is enforced by the build rather than by remembering.
 */
class NoAndroidApiLeakTest {

    private static final String CORE = "com/aaravlabs/autonomy/";

    /**
     * JDK packages that require Android API 26, above the SDK's declared minimum
     * of 24. {@code java/time} and {@code java/nio/file} are the two an
     * autonomous helper reaches for by instinct: measuring a state with
     * {@code Duration}, or naming a recording with {@code Path}.
     */
    private static final String[] API_26_PACKAGES = {
            "java/time/",        // Duration, Instant, LocalDateTime
            "java/nio/file/",    // Path, Files, Paths
    };

    /**
     * Legal on API 24, but worth knowing about: {@code Base64} is API 26 and
     * {@code String.join} is 26 as well, while {@code Objects.requireNonNull}
     * and {@code Collections.unmodifiableList} are safe everywhere.
     */
    private static final String[] API_26_MEMBERS = {
            "java/util/Base64",
            "java/util/StringJoiner",
    };

    /**
     * Methods that need API 26 and cannot be caught by a type scan.
     *
     * <p>{@code String.join} was shipped in {@code StateMachineOpMode}, called on the telemetry
     * line that reports a state which forgot its end condition -- so on an API 24/25 Robot
     * Controller the library threw {@code NoSuchMethodError} at the exact moment it was trying to
     * explain a problem. It was invisible to the scan above for a reason worth recording: calling
     * it emits a {@code Methodref} to {@code java/lang/String}, an owner every {@code toString()}
     * in the library already references, and the name {@code join} sits in the pool as its own
     * entry with nothing to tie it to that owner. Banning {@code java/util/StringJoiner} cannot
     * help either, because that class is only ever referenced by {@code String.join}'s own body.
     *
     * <p>Hence a method-reference scan, which resolves owner and name together. See
     * {@link CompiledClasses#findMethodReferences}.
     */
    private static final String[] API_26_METHODS = {
            "java/lang/String#join",
    };

    @Test
    @DisplayName("references no JDK type that needs Android API 26")
    void referencesNoApi26OnlyTypes() throws IOException {
        String[] forbidden = concat(API_26_PACKAGES, API_26_MEMBERS);

        List<String> leaks = CompiledClasses.findReferences(CORE, forbidden);

        if (!leaks.isEmpty()) {
            fail("the state framework references types that need Android API 26, but the FTC SDK"
                    + " declares minSdkVersion=24. On an API 24/25 Robot Controller this throws"
                    + " NoClassDefFoundError the first time the state runs. Time the state with"
                    + " System.nanoTime() and arithmetic instead. Offending references:\n  "
                    + String.join("\n  ", leaks));
        }
    }

    @Test
    @DisplayName("calls no JDK method that needs Android API 26")
    void referencesNoApi26OnlyMethods() throws IOException {
        List<String> leaks = CompiledClasses.findMethodReferences(CORE, API_26_METHODS);

        if (!leaks.isEmpty()) {
            fail("the state framework calls methods that need Android API 26, but the FTC SDK"
                    + " declares minSdkVersion=24. On an API 24/25 Robot Controller this throws"
                    + " NoSuchMethodError the first time that code path runs. Build the string"
                    + " with a StringBuilder instead. Offending references:\n  "
                    + String.join("\n  ", leaks));
        }
    }

    /** Class file major version for Java 11. */
    private static final int JAVA_11_MAJOR_VERSION = 55;

    @Test
    @DisplayName("compiles to Java 11 bytecode, so a JDK 11 consumer can read it")
    void targetsJava11() throws IOException {
        List<String> classes = CompiledClasses.list(CORE);

        assertTrue(!classes.isEmpty(), "no compiled classes found; the scan would pass vacuously");

        List<String> tooNew = new ArrayList<>();
        for (String name : classes) {
            int major = CompiledClasses.majorVersion(name);
            if (major > JAVA_11_MAJOR_VERSION) {
                tooNew.add(name + " -> major version " + major);
            }
        }

        if (!tooNew.isEmpty()) {
            fail("these classes are newer than Java 11 bytecode. Autonomy is consumed alongside"
                    + " Synapse and Engram, which are both Java 11, so a class a JDK 11 consumer"
                    + " cannot read breaks the team project. `options.release = 11` in build.gradle"
                    + " is what prevents this. Offending classes:\n  "
                    + String.join("\n  ", tooNew));
        }
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
