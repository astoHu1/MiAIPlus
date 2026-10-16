package io.github.miaiplus;

import java.util.Set;

/** A launch request succeeds only after the target owns the focused window. */
final class LaunchVerifier {
    static final String XIAOMI_CONFIRMATION = "com.miui.securitycenter:launch-confirmation";
    static final String XIAOMI_AUTHORIZATION = "com.miui.securitycenter:authorization";
    private static final long TIMEOUT_MS = 10_000;
    private static final long STABLE_MS = 500;
    private static final Set<String> AUTHORIZATION_PACKAGES = Set.of(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.miui.packageinstaller", "com.miui.securitycore",
        "com.lbe.security.miui", XIAOMI_CONFIRMATION, XIAOMI_AUTHORIZATION);
    interface Driver {
        String foreground() throws Exception;
        void start() throws Exception;
        default boolean allowLaunchPrompt() throws Exception { return false; }
        void check();
        long now();
        void pause(long millis) throws Exception;
    }
    private LaunchVerifier() {}
    static String classifyForeground(String owner, String permissionGroup) {
        if (!"com.miui.securitycenter".equals(owner) || permissionGroup.isBlank()) return owner;
        return "启动应用".equals(permissionGroup) ? XIAOMI_CONFIRMATION : XIAOMI_AUTHORIZATION;
    }
    static void launch(String target, Driver driver) throws Exception {
        driver.check();
        String foreground = driver.foreground();
        boolean allowedPrompt = XIAOMI_CONFIRMATION.equals(foreground)
            && driver.allowLaunchPrompt();
        if (!allowedPrompt) rejectAuthorization(target, foreground);
        boolean requested = !target.equals(foreground) && !allowedPrompt;
        if (requested) {
            driver.check();
            driver.start(); // At most once: a blocked request must not spawn a retry loop.
        }
        long deadline = driver.now() + TIMEOUT_MS;
        long focusedSince = -1;
        while (true) {
            driver.check();
            foreground = driver.foreground();
            if (requested && !allowedPrompt && XIAOMI_CONFIRMATION.equals(foreground)
                && driver.allowLaunchPrompt()) {
                allowedPrompt = true;
                driver.pause(250);
                continue;
            }
            // Give the clicked prompt time to disappear; never click twice.
            if (!(allowedPrompt && XIAOMI_CONFIRMATION.equals(foreground)))
                rejectAuthorization(target, foreground);
            long now = driver.now();
            if (target.equals(foreground)) {
                if (focusedSince < 0) focusedSince = now;
                if (now - focusedSince >= STABLE_MS) return;
            } else focusedSince = -1;
            if (now >= deadline)
                throw new IllegalStateException("PAUSE：打开应用后未确认目标进入前台，已暂停。可能被系统限制、启动失败或跳到了其他应用，请手动打开目标应用后重新发起任务；不要重复启动或报告已打开。");
            driver.pause(250);
        }
    }
    private static void rejectAuthorization(String target, String foreground) {
        if (!target.equals(foreground) && AUTHORIZATION_PACKAGES.contains(foreground))
            throw new IllegalStateException("PAUSE：打开应用遇到系统授权或安全提示，已暂停。该提示不符合当前应用启动确认，未自动允许；请手动处理后重新发起任务。");
    }
}
