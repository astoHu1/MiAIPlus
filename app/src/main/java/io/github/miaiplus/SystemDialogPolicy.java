package io.github.miaiplus;

import java.util.Set;

/** Readable system UI is observed normally; granting a system privilege requires task-scoped review. */
final class SystemDialogPolicy {
    private static final Set<String> AUTHORIZATION_PACKAGES = Set.of(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.miui.packageinstaller", "com.miui.securitycore", "com.lbe.security.miui");

    private SystemDialogPolicy() { }

    static boolean requiresApproval(ScreenService.Frame frame) {
        return frame != null && requiresApproval(frame.foreground(), frame.tree());
    }

    static boolean requiresApproval(String foreground, String tree) {
        String owner = owner(foreground);
        if (AUTHORIZATION_PACKAGES.contains(owner)) return true;
        if (!owner.equals("com.miui.securitycenter")) return false;
        String group = permissionGroup(tree);
        // Security Center also owns ordinary cleaning/battery pages. Its permission title identifies the
        // authorization UI; package ownership alone must not prohibit browsing the application.
        return !group.isEmpty() && !isLaunchGroup(group);
    }

    static boolean requiresManual(ScreenService.Frame frame) {
        if (frame == null || !requiresApproval(frame)) return false;
        String owner = owner(frame.foreground());
        if (owner.contains("packageinstaller")) return true;
        String text = authorizationTitles(frame.tree());
        for (String term : new String[]{"设备管理", "无障碍", "安装未知应用", "未知来源", "安装应用",
            "关闭安全", "关闭保护", "关闭金融保护", "停用保护",
            "device admin", "accessibility service", "unknown sources", "disable protection"}) {
            if (text.toLowerCase(java.util.Locale.ROOT).contains(term)) return true;
        }
        return false;
    }

    /** Parent nodes often aggregate the whole page, including other choices and unrelated warnings. */
    private static String authorizationTitles(String tree) {
        if (tree == null) return "";
        StringBuilder result = new StringBuilder();
        for (String line : tree.split("\n")) {
            boolean title = false;
            for (String id : new String[]{"com.miui.securitycenter:id/permission_group_title",
                "com.android.permissioncontroller:id/permission_title",
                "com.android.permissioncontroller:id/permission_message",
                "com.google.android.permissioncontroller:id/permission_title",
                "com.google.android.permissioncontroller:id/permission_message",
                "com.lbe.security.miui:id/permission_title", "com.lbe.security.miui:id/permission_group_title",
                "android:id/alertTitle"}) {
                if (line.contains(" id=" + id + " ")) { title = true; break; }
            }
            if (!title) continue;
            int start = line.indexOf(" text=");
            if (start < 0) continue;
            String value = line.substring(start + 6);
            int description = value.indexOf(" description=");
            if (description >= 0) value = value.substring(0, description);
            result.append(value).append('\n');
        }
        return result.toString();
    }

    static boolean isAppSelection(String foreground, String tree) {
        String owner = owner(foreground);
        // Physical observations expose the root's package, without its Activity. Recent Android
        // sharesheets can also omit the chooser title, so this dedicated system package is enough.
        if (owner.equals("com.android.intentresolver")) return true;
        if (foreground != null && (owner.equals("android") || owner.equals("com.android.intentresolver"))
            && (foreground.contains("ResolverActivity") || foreground.contains("ChooserActivity"))) return true;
        if (owner.equals("com.miui.securitycenter") && isLaunchGroup(permissionGroup(tree))) return true;
        if (!(owner.equals("android") || owner.equals("com.android.intentresolver")
            || owner.equals("com.miui.securitycenter"))) return false;
        // Do not exempt a privilege grant just because its explanatory text mentions choosing an app.
        if (!permissionGroup(tree).isEmpty()) return false;
        return hasText(tree, "选择应用") || hasText(tree, "打开方式") || hasText(tree, "使用以下应用打开")
            || hasText(tree, "Complete action using") || hasText(tree, "Open with");
    }

    static boolean changesSystemUi(Action action) {
        return !Set.of("launch", "wait", "back", "home").contains(action.name);
    }

    /** A grant never authorizes a different prompt that appeared while the user/model was deciding. */
    static void verify(Action action, ScreenService.Frame planned, ScreenService.Frame current, boolean approved)
        throws ScreenService.ObservationChanged {
        if (!changesSystemUi(action) || !(requiresApproval(current) || requiresApproval(planned))) return;
        if (!approved || planned == null || !PageGuard.same(PageGuard.anchor(planned), PageGuard.anchor(current)))
            throw new ScreenService.ObservationChanged();
    }

    static String owner(String foreground) {
        if (foreground == null) return "";
        int slash = foreground.indexOf('/');
        return slash < 0 ? foreground : foreground.substring(0, slash);
    }

    private static boolean isLaunchGroup(String group) {
        return group.equals("启动应用") || group.equals("打开应用") || group.equals("选择应用");
    }

    private static String permissionGroup(String tree) {
        if (tree == null) return "";
        String found = "";
        for (String line : tree.split("\n")) {
            if (!line.contains(" id=com.miui.securitycenter:id/permission_group_title ")) continue;
            int start = line.indexOf(" text=");
            if (start < 0) return "未知授权";
            String value = line.substring(start + 6);
            int description = value.indexOf(" description=");
            if (description >= 0) value = value.substring(0, description);
            if (!found.isEmpty()) return "未知授权";
            found = value.strip();
            if (found.isEmpty()) return "未知授权";
        }
        return found;
    }

    private static boolean hasText(String tree, String text) {
        if (tree == null) return false;
        for (String line : tree.split("\n")) {
            int start = line.indexOf(" text=");
            if (start >= 0 && line.substring(start + 6).equals(text)) return true;
        }
        return false;
    }
}
