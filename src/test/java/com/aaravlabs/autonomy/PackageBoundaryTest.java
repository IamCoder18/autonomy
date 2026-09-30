package com.aaravlabs.autonomy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the boundary that makes this library worth having: the state framework
 * in {@code com.aaravlabs.autonomy} never touches the robot, and all robot
 * coupling lives in {@code com.aaravlabs.autonomy.ftc}.
 *
 * <p>That boundary is the whole reason the behaviour in this package can be
 * tested on a desktop JVM, and the reason a team's own states can be unit
 * tested without a robot. It is also the easiest thing in the codebase to
 * erode: a state that wants a motor will "just" import {@code DcMotorEx} and
 * reach for the hardware map, and nothing will complain.
 *
 * <p>So it is enforced here rather than trusted. The scan covers the non-{@code
 * ftc} classes only, which is precisely the package that must stay clean.
 */
class PackageBoundaryTest {

    /** The package that must stay free of robot types. */
    private static final String CORE = "com/aaravlabs/autonomy/";

    /** The one package inside the library that is allowed to be coupled to the robot. */
    private static final String ADAPTER = "com/aaravlabs/autonomy/ftc/";

    /**
     * Internal-name prefixes the core is not allowed to mention. The adapter's
     * own package is excluded from the scan rather than listed, so
     * {@code StateMachineOpMode} stays free to use all of it.
     */
    private static final String[] FORBIDDEN = {
            "com/qualcomm/",               // the FTC SDK
            "android/",                    // the Android framework
            "com/aaravlabs/synapse/",      // Synapse
            "org/firstinspires/",          // the FTC SDK's other namespace
    };

    @Test
    @DisplayName("the state framework references no robot, Android, or Synapse type")
    void theCoreReferencesNoRobotTypes() throws IOException {
        List<String> leaks = CompiledClasses.findReferences(CORE, FORBIDDEN);

        // Drop the adapter and anything nested under it: the boundary is about
        // the framework, not about the one class allowed to be coupled.
        List<String> real = new ArrayList<>();
        for (String hit : leaks) {
            if (!hit.startsWith(ADAPTER)) {
                real.add(hit);
            }
        }

        if (!real.isEmpty()) {
            fail("com.aaravlabs.autonomy must stay plain Java, so its behaviour can be unit tested"
                    + " on a desktop JVM. A class that needs the robot belongs in"
                    + " com.aaravlabs.autonomy.ftc instead, and a state should take its devices"
                    + " through its constructor. Offending references:\n  "
                    + String.join("\n  ", real));
        }
    }

    @Test
    @DisplayName("declares no static mutable state, so one run cannot leak into the next")
    void theCoreDeclaresNoStaticMutableState() throws Exception {
        // A static field on a State or StateMachine would be shared across every
        // machine in the process and would survive stop(), which is the same class
        // of bug the StateMachine javadoc warns about ("its states carry whatever
        // they accumulated during the previous run") but applied to the class
        // rather than the instance. The only statics the framework wants are the
        // immutable NANOS_PER_SECOND and RUN_ONCE constants, so the rule reduces
        // to: any static field must be final.
        List<String> classes = CompiledClasses.list(CORE);

        assertTrue(!classes.isEmpty(), "no compiled classes found; the scan would pass vacuously");

        List<String> offenders = new ArrayList<>();
        for (String relativeName : classes) {
            if (relativeName.endsWith("package-info.class")) {
                continue;
            }
            String binaryName = relativeName
                    .substring(0, relativeName.length() - ".class".length())
                    .replace('/', '.');
            for (Field field : Class.forName(binaryName).getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                    offenders.add(binaryName + "#" + field.getName());
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("the state framework must hold no mutable static state. A non-final static field"
                    + " is shared across every StateMachine in the process and survives stop(),"
                    + " so one run's leftovers leak into the next. Offending fields:\n  "
                    + String.join("\n  ", offenders));
        }
    }
}
