package com.qualcomm.robotcore.eventloop.opmode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stub of the FTC SDK's {@code @TeleOp} marker.
 *
 * <p>See {@link OpMode} for why this exists. The retention must be
 * {@link RetentionPolicy#RUNTIME} to match the real SDK.
 *
 * <p><strong>Transcribed from RobotCore 11.2.1, verified by
 * {@code FtcStubFidelityTest}.</strong> Unlike {@link Autonomous}, the real
 * {@code TeleOp} declares only two members, both {@link String} with an
 * empty-string default:
 *
 * <pre>{@code
 * String name() default "";
 * String group() default "";
 * }</pre>
 *
 * <p>This stub used to declare five, adding {@code groupAlt()},
 * {@code preselectTeleOp()}, and {@code preselectAutonomous()} -- again lifted
 * from {@code LinearOp}, and again with {@code preselectTeleOp()} typed
 * {@code int} where any real declaration would be {@link String}.
 *
 * <p>A {@code @TeleOp} OpMode is the first thing a team writes when it adopts
 * Autonomy, so this is the annotation most likely to be used against the stub,
 * which makes the phantom members a live hazard rather than a latent one.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TeleOp {

    String name() default "";

    String group() default "";
}