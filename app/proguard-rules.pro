# Keep the existing module/runtime ABI and reflection entry points intact. Only the
# settings frontend and its Compose/Miuix dependencies are eligible for shrinking.
-keep class !io.github.miaiplus.MainActivity*,!io.github.miaiplus.SettingsState*,!io.github.miaiplus.ProfileDraft*,!io.github.miaiplus.BackgroundCheck*,!io.github.miaiplus.VirtualScreenState*,!io.github.miaiplus.DisplayProbe*,!io.github.miaiplus.ProbeApp*,!io.github.miaiplus.SettingsIcons*,io.github.miaiplus.** { *; }
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.api.**
# Preserve the frontend ABI for the separately installed UI instrumentation APK.
# Method bodies can still be optimized; Compose implementation classes can shrink.
-keep,allowoptimization class io.github.miaiplus.MainActivity { *; }
-keep,allowoptimization class io.github.miaiplus.SettingsState { *; }
-keep,allowoptimization class io.github.miaiplus.ProfileDraft { *; }
-keep,allowoptimization class io.github.miaiplus.BackgroundCheck** { *; }
-keep,allowoptimization class androidx.lifecycle.ViewModelProvider { *; }

-keep,allowoptimization class io.github.miaiplus.VirtualScreenState { *; }
-keep,allowoptimization class io.github.miaiplus.DisplayProbe** { *; }
-keep,allowoptimization class io.github.miaiplus.ProbeApp { *; }
