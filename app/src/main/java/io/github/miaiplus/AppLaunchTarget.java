package io.github.miaiplus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Only launcher entries supplied by PackageManager are eligible for app launch. */
record AppLaunchTarget(String packageName, String activityName, String label) {
    static AppLaunchTarget resolve(String requested, List<AppLaunchTarget> entries) {
        String app = requested.strip();
        for (AppLaunchTarget entry : entries) if (entry.packageName.equals(app)) return entry;
        Set<String> matches = new LinkedHashSet<>();
        AppLaunchTarget found = null;
        for (AppLaunchTarget entry : entries) {
            if (!entry.label.equals(app)) continue;
            matches.add(entry.packageName);
            if (found == null) found = entry;
        }
        if (matches.size() > 1) throw new IllegalStateException("应用名称不唯一，请从可启动应用列表指定包名");
        if (found == null) throw new IllegalStateException("应用未安装、已停用或无可见启动入口，请从可启动应用列表选择包名");
        return found;
    }
}
