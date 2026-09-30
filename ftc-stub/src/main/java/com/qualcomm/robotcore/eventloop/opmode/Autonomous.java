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
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Autonomous {

    String name() default "";

    String group() default "";

    String groupAlt() default "";

    int preselectTeleOp() default -1;

    int preselectAutonomous() default -1;

    String initOp() default "";
}
