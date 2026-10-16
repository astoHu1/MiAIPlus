package io.github.miaiplus;

import java.util.List;

/** Recognize only Xiaomi's per-launch confirmation for the current caller and target. */
final class LaunchPrompt {
    private LaunchPrompt() {}
    static boolean matches(String owner, String group, String request, List<String> texts, List<String> callers, List<String> targets) {
        if (!"com.miui.securitycenter".equals(owner) || !"启动应用".equals(group)) return false;
        if (!texts.contains("本次允许") || !(texts.contains("拒绝") || texts.contains("取消"))) return false;
        String value = normalize(request);
        for (String caller : callers) for (String target : targets) {
            String from = normalize(caller), to = normalize(target);
            if (from.isEmpty() || to.isEmpty()) continue;
            for (String verb : new String[]{"想要打开", "尝试打开", "正在尝试打开"}) {
                String expected = from + verb + to;
                if (value.equals(expected) || value.equals(expected + "，是否允许？")
                    || value.equals(expected + "是否允许？") || value.equals(expected + "，是否允许?")
                    || value.equals(expected + "是否允许?")) return true;
            }
        }
        return false;
    }
    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s\\p{Z}“”‘’\"']", "");
    }
}
