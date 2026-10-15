package io.github.miaiplus;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;
import java.util.Map;

/** Typed native function entry avoids a second layer of CLI query quoting. */
public final class NativeComputerTool {
    public static final String NAME = "miaiplus_computer";
    private static final String DESCRIPTION = "本机手机控屏。start时传goal取得session和观察；然后每次传session及一个action对象，按新观察继续调用直到finish。无需额外API，由你逐步规划。默认使用截图JPEG像素坐标(image_pixels)，不是0..1000；也可显式选screen_pixels或normalized，必须匹配观察尺寸。不要复述CONTINUE或用自然语言替代action。系统专用工具优先。";
    private static final String SCHEMA = """
        {"type":"object","properties":{
          "goal":{"type":"string","description":"仅首次开始时填写完整任务"},
          "coordinate_space":{"type":"string","enum":["image_pixels","screen_pixels","normalized"],"description":"默认image_pixels：按附图真实JPEG宽高的像素；screen_pixels按无障碍树物理屏幕像素；normalized为0..1000"},
          "session":{"type":"string","description":"后续动作必填，取自前一步返回值"},
          "action":{"type":"object","properties":{
            "action":{"type":"string","enum":["tap","double_tap","long_press","swipe","type","launch","back","home","wait","finish","take_over","ask_user"]},
            "element":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2},
            "start":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2},
            "end":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2},
            "text":{"type":"string"},"app":{"type":"string","description":"应用包名"},
            "message":{"type":"string"},"requires_confirmation":{"type":"boolean"},"duration_ms":{"type":"integer"}
          },"required":["action"]}
        }}
        """;
    private static Object definition;
    private static volatile boolean registered;
    static boolean available(Context context) {
        try {
            Bundle flags = context.getContentResolver().call(BridgeProvider.URI, "flags", null, null);
            return flags != null && flags.getBoolean("enabled") && flags.getBoolean("nativePlanner");
        } catch (Exception e) { return false; }
    }
    static Object json(ClassLoader loader, String text) {
        return XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("s92.b", loader), "d"), "parseToJsonElement", text);
    }
    public static void install(Context context, ClassLoader loader, List<XC_MethodHook.Unhook> hooks) {
        Object schema = json(loader, SCHEMA);
        definition = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.llm.model.ToolDefinition", loader), NAME, DESCRIPTION, schema);
        Class<?> toolType = XposedHelpers.findClass("com.aios.osbot.tools.Tool", loader);
        Object tool = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{toolType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getName", "getDisplayName" -> NAME;
                case "getDescription", "getSummary", "getSearchHint" -> DESCRIPTION;
                case "getParameters" -> schema;
                case "getCategory" -> "phone";
                case "getRejects" -> "";
                case "getScope", "getTags" -> List.of();
                case "getDisplayDescriptionResId" -> 0;
                case "getTimeoutMs", "resolveTimeoutMs" -> 245000L;
                case "isStoppable" -> false;
                case "onCancel" -> XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.Unit", loader), "INSTANCE");
                case "execute" -> {
                    String text;
                    try {
                        if (!available(context)) throw new IllegalStateException("本机逐步控屏未启用");
                        org.json.JSONObject request = new org.json.JSONObject(args[0].toString());
                        if (request.has("action") && !request.has("coordinate_space")) request.put("coordinate_space", "image_pixels");
                        String query = request.has("action") ? "MIAIPLUS:" + request : request.getString("goal");
                        Object service = XposedHelpers.newInstance(XposedHelpers.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
                        Object result = XposedHelpers.callMethod(service, "controlApp", query);
                        text = String.valueOf(XposedHelpers.callMethod(result, "getText"));
                    } catch (Exception e) { text = "控屏停止：" + (e instanceof org.json.JSONException ? "缺少goal或session/action参数" : e.getMessage()); }
                    yield XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.tools.ToolResult$Success", loader), text, Map.of(), null, List.of(), false, null, false);
                }
                case "toString" -> NAME;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Class<?> registry = XposedHelpers.findClass("com.aios.osbot.tools.ToolRegistry", loader);
        hooks.addAll(XposedBridge.hookAllConstructors(registry, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { XposedHelpers.callMethod(p.thisObject, "registerSystem", tool); registered = true; }
        }));
    }
    static void addDefinition(Context context, XC_MethodHook.MethodHookParam p) {
        if (!registered || !available(context) || definition == null || !(p.args[1] instanceof List<?> old)) return;
        for (Object value : old) if (NAME.equals(XposedHelpers.callMethod(value, "getName"))) return;
        var tools = new java.util.ArrayList<Object>(old); tools.add(definition); p.args[1] = tools;
    }
}
