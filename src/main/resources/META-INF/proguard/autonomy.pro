# R8/ProGuard keep rules for com.aaravlabs:autonomy.
#
# Autonomy uses no reflection, no annotations scanned at runtime, and no
# dynamic class loading, so in principle nothing here needs keeping. These
# rules exist anyway, for two reasons:
#
#   1. `StateMachineOpMode` is loaded by name: the Robot Controller reflects
#      over the APK to find every OpMode subclass, then instantiates it. If a
#      team builds a release APK with minification on, a stripped or renamed
#      superclass is an OpMode that silently does not appear on the Driver
#      Station.
#   2. A team's own states extend AbstractState and are referenced only
#      through the List<State> returned by buildStates(). R8 can see that
#      reference, but keeping the base class costs nothing and removes a
#      whole class of "worked at home, vanished on the robot" reports.
#
# Include in your FTC project by adding this to your app's proguard-rules.pro:
#
#   -keep public class com.aaravlabs.autonomy.** { public *; }

# The public API of the state framework.
-keep public interface com.aaravlabs.autonomy.** { *; }
-keep public class com.aaravlabs.autonomy.** { public *; }

# StateMachineOpMode is discovered reflectively by the Robot Controller.
-keep public class com.aaravlabs.autonomy.ftc.StateMachineOpMode { public *; }

# AbstractState.endCondition() is final and called through the State interface;
# keep the attributes R8 needs to keep the generic signatures intact.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisible*Annotations
