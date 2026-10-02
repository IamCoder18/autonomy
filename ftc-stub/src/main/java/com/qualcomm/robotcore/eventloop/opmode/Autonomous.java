package com.qualcomm.robotcore.eventloop.opmode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stub of the FTC SDK's {@code @Autonomous} marker.
 *
 * <p>See {@link OpMode} for why this exists. The Robot Controller scans
 * compiled classes for this annotation at runtime, so the retention here must
 * be {@link RetentionPolicy#RUNTIME} to match the real SDK.
 *
 * <p><strong>Transcribed from RobotCore 11.2.1, verified by
 * {@code FtcStubFidelityTest}.</strong> The interface declares exactly three
 * members, all {@link String} with an empty-string default:
 *
 * <pre>{@code
 * String name() default "";
 * String group() default "";
 * String preselectTeleOp() default "";
 * }</pre>
 *
 * <p>This stub used to declare six members, three of which the real interface
 * does not have: {@code groupAlt()}, {@code preselectAutonomous()}, and
 * {@code initOp()} are declared by {@code LinearOp}, not by {@code Autonomous}.
 * Worse, {@code preselectTeleOp()} was declared {@code int} where the SDK
 * declares {@code String}, which is a descriptor mismatch -- {@code ()I} against
 * {@code ()Ljava/lang/String;}. Writing an OpMode against this stub and reading
 * an annotation member off it would have produced a jar that links here and
 * throws {@code NoSuchMethodError} on a Robot Controller.
 *
 * <p>Autonomy itself never uses this annotation, and the stub is
 * {@code compileOnly}, so nothing shipped was affected. That is luck, not
 * design: the annotation classes are part of the stub package and so were inside
 * the blast radius of a guard that used to check only the three classes the
 * adapter referenced.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Autonomous {

    String name() default "";

    String group() default "";

    String preselectTeleOp() default "";
}