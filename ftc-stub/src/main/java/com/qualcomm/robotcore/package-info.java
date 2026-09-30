/**
 * Hand-written compile-time stubs of the FTC SDK types Autonomy references.
 *
 * <p>The real {@code org.firstinspires.ftc:*} artifacts are published only as
 * Android AARs, which a plain {@code java-library} project cannot put on a
 * javac classpath. Rather than adopt the Android Gradle Plugin just to compile
 * one adapter class, Autonomy compiles against these stubs.
 *
 * <p>The Robot Controller app provides the genuine classes at runtime, so this
 * source set is {@code compileOnly} and is never packaged or published. The
 * jar built from it ({@code libs/ftc-sdk-stub.jar}) exists only to satisfy the
 * compiler.
 *
 * <p>The one rule: <strong>signatures here must match the real SDK exactly.</strong>
 * A stub that drifts from the SDK compiles cleanly and then throws
 * {@code NoSuchMethodError} on a competition day. When bumping the SDK version
 * in {@code gradle.properties}, re-check every signature in this package
 * against the SDK release notes.
 */
package com.qualcomm.robotcore;
