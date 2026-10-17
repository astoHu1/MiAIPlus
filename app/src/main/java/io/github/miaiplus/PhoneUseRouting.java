package io.github.miaiplus;

import org.json.JSONObject;

/** Explicit tool routing is scoped to a request, never written into the user's default settings. */
final class PhoneUseRouting {
    static final String START_PREFIX = "MIAIPLUS_START:";
    static final String FOREGROUND_TOOL = "miaiplus_phone_use";
    static final String BACKGROUND_TOOL = "miaiplus_background_phone_use";

    static boolean background(String tool) {
        if (FOREGROUND_TOOL.equals(tool)) return false;
        if (BACKGROUND_TOOL.equals(tool)) return true;
        throw new IllegalArgumentException("未知控屏工具");
    }

    static String encode(String tool, JSONObject request) throws Exception {
        background(tool);
        if (request.has("execution_mode") || request.has("background"))
            throw new IllegalArgumentException("执行屏幕由所调用的工具决定，请勿在会话内覆盖");
        if (request.has("action")) {
            if (!request.has("coordinate_space")) request.put("coordinate_space", "image_pixels");
            request.put("origin_tool", tool);
            return "MIAIPLUS:" + request;
        }
        String goal = request.getString("goal");
        if (goal.isBlank()) throw new IllegalArgumentException("任务内容为空");
        return START_PREFIX + new JSONObject().put("goal", goal).put("origin_tool", tool);
    }

    record Request(String query, boolean background, String sourceTool) { }

    static Request decode(String query, boolean defaultBackground) throws Exception {
        if (query != null && query.startsWith(START_PREFIX)) {
            JSONObject start = new JSONObject(query.substring(START_PREFIX.length()));
            String tool = start.getString("origin_tool");
            String goal = start.getString("goal");
            if (goal.isBlank() || goal.length() > 16000) throw new IllegalArgumentException("任务内容为空或过长");
            return new Request(goal, background(tool), tool);
        }
        if (query != null && query.startsWith("MIAIPLUS:")) {
            JSONObject step = new JSONObject(query.substring(9));
            String tool = step.optString("origin_tool", "");
            if (!tool.isEmpty()) background(tool);
            if (step.has("execution_mode") || step.has("background"))
                throw new IllegalArgumentException("执行屏幕不能在会话内覆盖");
            return new Request(query, defaultBackground, tool);
        }
        return new Request(query, defaultBackground, "");
    }

    static void verifyContinuation(String expectedTool, String actualTool) {
        if (!expectedTool.equals(actualTool))
            throw new IllegalStateException("请继续使用启动此会话的工具 "
                + (expectedTool.isEmpty() ? "voiceassist__controlApp" : expectedTool));
    }
}
