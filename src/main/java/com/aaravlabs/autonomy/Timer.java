package com.aaravlabs.autonomy;

import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A stopwatch and a timeout in one object: how long a state has been running, and whether it has
 * been running too long.
 *
 * <p>Timers exist because the two things a state wants to know about time are the same two things
 * this answers, and answering them with a hand-rolled {@code long} in every state gets them subtly
 * wrong in the same way each time.
 *
 * <h2>As a timeout</h2>
 *
 * <p>The duration is given once, to {@link #start(double)}, and the check refers back to it. That
 * is the whole point: writing {@code setEndCondition(() -> elapsed() > 0.2)} next to a
 * {@code 0.2} stored in a field is how a timeout ends up checking a different number from the one
 * it was written for.
 *
 * <pre>{@code
 * public class ShootState extends AbstractState {
 *     @Override
 *     public void init() {
 *         shooter.run();
 *         startTimer(0.2);                       // give up after 0.2 s
 *         setEndCondition(() -> timer().hasElapsed() || shooter::isShootComplete);
 *     }
 *
 *     @Override
 *     public void loop() {}
 *
 *     @Override
 *     public void stop() { shooter.stop(); }
 * }
 * }</pre>
 *
 * <h2>As a stopwatch</h2>
 *
 * <pre>{@code
 * @Override
 * public void init() { startTimer(); }
 * @Override public void loop() { telemetry.addLine(timer().toString()); }
 * }</pre>
 *
 * <h2>When time starts counting</h2>
 *
 * <p>A timer does not run until {@link #start()} or {@link #start(double)} is called. That is
 * deliberate, and it is why a timer field can be initialised in a constructor without the time it
 * was built counting against it: a route is built by {@code StateMachineOpMode.buildStates()} while
 * the Driver Station shows INIT, and the driver may sit there for an unbounded time before pressing
 * START. A timer that began at construction would spend that time before the robot moved.
 *
 * <p>So call it from {@link State#init()}. For the first state of a route that is the moment
 * {@code start()} is called, and for every later state it is the iteration that enters it, so in
 * both cases the timer measures the time the state is actually running. {@link AbstractState} and
 * {@link Submachine} each carry one for this purpose, reached via {@code timer()}.
 *
 * <p>Not thread safe, like the runner that drives it.
 *
 * <p>Time is held as {@code long} nanoseconds because {@code java.time} is Android API 26 and the
 * FTC SDK declares {@code minSdkVersion=24}; see {@code NoAndroidApiLeakTest}.
 */
public final class Timer {

    /**
     * The target of a timer started with no duration: far enough away that it is never reached.
     *
     * <p>Used as a sentinel rather than as a nullable or a companion flag, so that the check a
     * state actually runs per iteration is one subtraction and one comparison with no branch and no
     * allocation. It cannot be confused with a real target either, because a duration long enough
     * to saturate to {@link Long#MAX_VALUE} would not be reached within this process's lifetime
     * anyway.
     */
    private static final long NO_TARGET = Long.MAX_VALUE;

    private final String name;
    private final LongSupplier nanoTime;

    /** When {@link #start()} was called. Meaningless while {@link #running} is false. */
    private long startNanos = 0L;

    /** How long the timer may run, in nanoseconds, or {@link #NO_TARGET}. */
    private long targetNanos = NO_TARGET;

    private boolean running = false;

    /** Creates a timer named "Timer", measuring against {@link System#nanoTime()}. */
    public Timer() {
        this("Timer", System::nanoTime);
    }

    /**
     * Creates a timer measuring against {@link System#nanoTime()}.
     *
     * @param name what to call this timer in {@link #toString()}, e.g. "Climb"
     */
    public Timer(String name) {
        this(name, System::nanoTime);
    }

    /**
     * Creates a timer with an explicit clock, so a test outside this package can make timings
     * deterministic. The other constructors measure against {@link System#nanoTime()}.
     *
     * @throws NullPointerException if {@code name} or {@code nanoTime} is {@code null}
     */
    public Timer(String name, LongSupplier nanoTime) {
        this.name = Objects.requireNonNull(name, "name");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Starts the timer over, with no timeout: it counts upward and never expires.
     *
     * <p>Restarting resets the elapsed time to zero, so calling this on every entry is safe even
     * for a state that is entered more than once.
     */
    public void start() {
        startNanos = nanoTime.getAsLong();
        targetNanos = NO_TARGET;
        running = true;
    }

    /**
     * Starts the timer over, with a timeout of {@code seconds} from now.
     *
     * <p>The duration is remembered here and nowhere else. {@link #hasElapsed()} then reports
     * against this number, which is what stops the target and the check from drifting apart.
     *
     * @param seconds how long before {@link #hasElapsed()} returns {@code true}
     * @throws IllegalArgumentException if {@code seconds} is negative, NaN, or infinite
     */
    public void start(double seconds) {
        Timing.requireFiniteNonNegative(seconds);
        startNanos = nanoTime.getAsLong();
        // The double-to-long cast saturates rather than wrapping, so an absurdly long duration
        // lands on Long.MAX_VALUE and means "effectively never" instead of a moment in the past.
        targetNanos = (long) (seconds * Timing.NANOS_PER_SECOND);
        running = true;
    }

    /**
     * Stops the timer, discarding its elapsed time and any timeout.
     *
     * <p>After this, {@link #elapsedSeconds()} reads {@code 0} and {@link #hasElapsed()} is
     * {@code false}, so a timer cannot report time it is no longer measuring. This is the same
     * shape as {@link StateMachine#elapsedSeconds()} before {@code start()}.
     */
    public void stop() {
        running = false;
        targetNanos = NO_TARGET;
    }

    /**
     * Whether the timer has been started and not stopped.
     *
     * <p>A timer that was never started is not running, so a state that reads {@link #elapsedSeconds()}
     * without starting it gets {@code 0} rather than the time since the route was built.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * How long the timer has been running, in nanoseconds; {@code 0} if it is not running.
     *
     * <p>Computed by subtracting two readings rather than by comparing them against a precomputed
     * deadline, which is what keeps it correct when {@link System#nanoTime()} wraps, as it is
     * specified to do.
     */
    public long elapsedNanos() {
        return running ? nanoTime.getAsLong() - startNanos : 0L;
    }

    /** How long the timer has been running, in seconds; {@code 0} if it is not running. */
    public double elapsedSeconds() {
        return elapsedNanos() / (double) Timing.NANOS_PER_SECOND;
    }

    /**
     * Whether the duration passed to {@link #start(double)} has elapsed.
     *
     * <p>{@code false} if the timer is not running, and {@code false} forever if it was started
     * with {@link #start()} and so has no duration to be out-lived by.
     */
    public boolean hasElapsed() {
        return running && elapsedNanos() >= targetNanos;
    }

    /**
     * Whether {@code seconds} have elapsed since the timer started.
     *
     * <p>For a check made once, against a number that exists nowhere else. When the same timeout is
     * the end condition of a state, prefer {@link #start(double)} and {@link #hasElapsed()}, which
     * cannot be given two different numbers.
     *
     * @throws IllegalArgumentException if {@code seconds} is negative, NaN, or infinite
     */
    public boolean hasElapsed(double seconds) {
        Timing.requireFiniteNonNegative(seconds);
        return running && elapsedNanos() >= (long) (seconds * Timing.NANOS_PER_SECOND);
    }

    /**
     * How much of the duration passed to {@link #start(double)} is left, in seconds; {@code 0} once
     * it is gone, and {@code 0} if the timer is not running or was started with {@link #start()}.
     *
     * <p>Never negative, so this is safe to print as a countdown.
     */
    public double remainingSeconds() {
        if (!running || targetNanos == NO_TARGET) {
            return 0.0;
        }
        long left = targetNanos - elapsedNanos();
        return (left > 0L ? left : 0L) / (double) Timing.NANOS_PER_SECOND;
    }

    /**
     * The duration this timer will expire after, in seconds, or {@code -1} if it was started with
     * {@link #start()} and has no duration.
     *
     * <p>Provided so a state can report what it is timing itself against, and so a test can assert
     * that a target was recorded rather than inferring it from an elapsed time.
     */
    public double targetSeconds() {
        return targetNanos == NO_TARGET ? -1.0 : targetNanos / (double) Timing.NANOS_PER_SECOND;
    }

    /** What to call this timer on the Driver Station. */
    public String name() {
        return name;
    }

    /**
     * A readable one-liner, e.g. {@code "Climb: 1.25 s"}, for telemetry and logs.
     *
     * <p>Includes the timeout when there is one, so a line reads {@code "Climb: 1.25 s of 3.00 s"}
     * rather than making the reader cross-reference another line.
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(32);
        text.append(name).append(": ");
        text.append(String.format(Locale.US, "%.2f", elapsedSeconds()));
        text.append(" s");
        if (targetNanos != NO_TARGET) {
            text.append(" of ").append(String.format(Locale.US, "%.2f",
                    targetNanos / (double) Timing.NANOS_PER_SECOND)).append(" s");
        }
        return text.toString();
    }
}