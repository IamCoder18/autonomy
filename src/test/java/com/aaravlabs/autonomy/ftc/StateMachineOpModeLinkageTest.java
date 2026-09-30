package com.aaravlabs.autonomy.ftc;

import com.aaravlabs.autonomy.State;
import com.aaravlabs.autonomy.StateMachine;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that {@link StateMachineOpMode} still lines up with the Synapse release
 * it compiles against.
 *
 * <p>Every other test in this library runs on a plain desktop JVM and never
 * touches the adapter, because the adapter needs a real OpMode. That leaves the
 * one class most likely to break with no coverage at all. Its failure mode is
 * quiet and late: if a Synapse release renames {@code onSafeLoop()} or changes
 * {@code SafeOpMode}'s supertype, this class still compiles against a stale
 * stub, and the mismatch surfaces as an {@code AbstractMethodError} or a missing
 * method on a competition day.
 *
 * <p>So the contract is asserted here, by reflection, against the real Synapse
 * jar. When the Synapse dependency is bumped and one of these fails, that is the
 * signal to re-read the Synapse changelog and adjust this adapter deliberately.
 */
class StateMachineOpModeLinkageTest {

    private static final String SAFE_OP_MODE = "com.aaravlabs.synapse.ftc.SafeOpMode";
    private static final String SDK_OP_MODE = "com.qualcomm.robotcore.eventloop.opmode.OpMode";

    @Test
    @DisplayName("extends Synapse's SafeOpMode, which extends the SDK's OpMode")
    void sitsOnTopOfTheExpectedOpModeHierarchy() {
        Class<?> superclass = StateMachineOpMode.class.getSuperclass();

        assertEquals(SAFE_OP_MODE, superclass.getName(),
                "StateMachineOpMode must extend Synapse's SafeOpMode. If a Synapse release renamed"
                        + " or repackaged it, update this adapter and this test together.");
        assertEquals(SDK_OP_MODE, superclass.getSuperclass().getName(),
                "SafeOpMode must still extend the SDK's OpMode; that is what the Robot Controller"
                        + " discovers and instantiates.");
    }

    @Test
    @DisplayName("overrides all three Synapse lifecycle hooks it relies on")
    void overridesTheSafeLifecycleHooks() throws Exception {
        for (String hook : List.of("onSafeInit", "onSafeLoop", "onSafeStop")) {
            Method declared = findDeclared(StateMachineOpMode.class, hook);

            assertTrue(declared != null,
                    "StateMachineOpMode no longer overrides " + hook + "(). A Synapse release"
                            + " probably renamed it; the machine would silently stop advancing.");
            assertTrue(Modifier.isProtected(declared.getModifiers()),
                    hook + "() should stay protected, as it is in SafeOpMode");
        }
    }

    @Test
    @DisplayName("keeps the route hook abstract, so a subclass cannot forget it")
    void theRouteHookStaysAbstract() throws Exception {
        Method buildStates = findDeclared(StateMachineOpMode.class, "buildStates");

        assertTrue(buildStates != null, "buildStates() is the library's one extension point");
        assertTrue(Modifier.isAbstract(buildStates.getModifiers()),
                "buildStates() must stay abstract");
        assertTrue(Modifier.isProtected(buildStates.getModifiers()),
                "buildStates() should be protected, not public");
        assertEquals(List.class, buildStates.getReturnType(),
                "buildStates() must return a List so a route can be built inline");
        assertEquals(0, buildStates.getParameterCount(),
                "buildStates() must stay parameterless: Synapse's hardware map is already"
                        + " populated by the time it is called, so a parameter would only invite"
                        + " a team to pass something in that does not exist yet");
    }

    @Test
    @DisplayName("exposes the running machine to subclasses that need more than the route")
    void exposesTheMachine() throws Exception {
        Method machine = findDeclared(StateMachineOpMode.class, "machine");

        assertTrue(machine != null, "machine() is the documented escape hatch");
        assertEquals(StateMachine.class, machine.getReturnType());
        assertTrue(Modifier.isProtected(machine.getModifiers()),
                "machine() should be protected, not public");
    }

    @Test
    @DisplayName("drives states typed as State, so the adapter and the framework agree")
    void theRouteIsTypedAgainstTheFramework() {
        // buildStates() returning List<State> is erased at runtime, so the link
        // between the two packages is only visible in the generic signature.
        // Reading it here is what catches an accidental switch to a different
        // State type, which would compile in the adapter and fail in the team.
        Method buildStates;
        try {
            buildStates = StateMachineOpMode.class.getDeclaredMethod("buildStates");
        } catch (NoSuchMethodException e) {
            fail("buildStates() disappeared from StateMachineOpMode");
            return;
        }

        String genericReturn = buildStates.getGenericReturnType().getTypeName();
        assertEquals("java.util.List<" + State.class.getName() + ">", genericReturn,
                "buildStates() must return List<State>; the framework and the adapter must agree"
                        + " on the same State type");
    }

    private static Method findDeclared(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
