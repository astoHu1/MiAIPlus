package io.github.miaiplus;

import org.json.JSONObject;

/** Terminal control replies are protocol signals, never text found inside an observation. */
final class PhoneUseResult {
    private static final String STOP = "STOP：MiAIPlus 控屏已停止。";

    static String stopped(String reason) {
        if (reason != null && reason.startsWith(STOP)) return reason;
        return STOP + "任务未确认完成。原因：" + (reason == null || reason.isBlank() ? "控屏执行失败" : reason)
            + "\n本轮控屏已结束；不要重试旧动作、不要重新提交 goal 或改用其他工具重开同一任务。请向用户说明未完成及原因，等待用户明确发起新任务。";
    }

    static boolean endsNativeTurn(String text) {
        if (text == null) return false;
        if (terminalText(text)) return true;
        // Only unwrap known CLI result envelopes. A page/tree field must never become a stop signal.
        if (!text.startsWith("{")) return false;
        try {
            JSONObject value = new JSONObject(text);
            if (value.optInt("__envelope__") == 1) {
                Object data = value.opt("data");
                if (data instanceof String plain) return terminalText(plain);
                if (data instanceof JSONObject result) return terminalTextResult(result);
            }
            return terminalTextResult(value);
        } catch (org.json.JSONException invalid) { return false; }
    }

    private static boolean terminalText(String text) {
        return text.startsWith(STOP) || text.startsWith("PAUSE：");
    }

    private static boolean terminalTextResult(JSONObject result) {
        return result.optString("type").equals("text") && result.opt("text") instanceof String plain
            && terminalText(plain);
    }

    private PhoneUseResult() { }
}
