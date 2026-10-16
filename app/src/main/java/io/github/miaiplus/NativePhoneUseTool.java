package io.github.miaiplus;

import android.content.Context;
import android.os.Bundle;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.util.Map;

/** Typed native function entry avoids a second layer of CLI query quoting. */
public final class NativePhoneUseTool {
    public static final String NAME = "miaiplus_phone_use";
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
            "intent":{"type":"string","description":"每步用简短中文说明当前操作意图，例如点击搜索框、查找订单，显示在灵动岛；不含隐私内容、坐标或推理过程。不要用message填写普通意图。"},
            "text":{"type":"string"},"app":{"type":"string","description":"launch使用首次观察可启动应用列表中的包名，不猜测包名或Intent"},
            "message":{"type":"string"},"requires_confirmation":{"type":"boolean"},"duration_ms":{"type":"integer"}
          },"required":["action"]}
        }}
        """;
    private static Object definition;
    private static volatile boolean registered;
    private static final java.util.Set<Object> registries = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static void register(Object registry, Object tool) {
        Reflector.callMethod(registry, "registerSystem", tool);
        synchronized (registries) { registries.add(registry); }
        registered = true;
    }
    static void dispose() {
        synchronized (registries) {
            for (Object registry : registries) {
                Reflector.callMethod(registry, "unregister", NAME);
                ((java.util.Set<?>)Reflector.getObjectField(registry, "systemToolNames")).remove(NAME);
            }
            registries.clear();
        }
        registered = false; definition = null;
    }
    static boolean available(Context context) {
        try {
            Bundle flags = context.getContentResolver().call(BridgeProvider.URI, "flags", null, null);
            return flags != null && flags.getBoolean("enabled") && flags.getBoolean("nativePlanner");
        } catch (Exception e) { return false; }
    }
    static Object json(ClassLoader loader, String text) {
        return Reflector.callMethod(Reflector.getStaticObjectField(Reflector.findClass("s92.b", loader), "d"), "parseToJsonElement", text);
    }
    public static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) {
        Object schema = json(loader, SCHEMA);
        definition = Reflector.newInstance(Reflector.findClass("com.aios.osbot.llm.model.ToolDefinition", loader), NAME, DESCRIPTION, schema);
        Class<?> toolType = Reflector.findClass("com.aios.osbot.tools.Tool", loader);
        Object tool = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{toolType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getName" -> NAME;
                case "getDisplayName" -> "phone use";
                case "getDescription", "getSummary", "getSearchHint" -> DESCRIPTION;
                case "getParameters" -> schema;
                case "getCategory" -> "phone";
                case "getRejects" -> "";
                case "getScope", "getTags" -> List.of();
                case "getDisplayDescriptionResId" -> 0;
                case "getTimeoutMs", "resolveTimeoutMs" -> 245000L;
                case "isStoppable" -> false;
                case "onCancel" -> Reflector.getStaticObjectField(Reflector.findClass("kotlin.Unit", loader), "INSTANCE");
                case "execute" -> {
                    Hooks.enter();
                    try {
                    String text;
                    try {
                        if (!available(context)) throw new IllegalStateException("本机逐步控屏未启用");
                        org.json.JSONObject request = new org.json.JSONObject(args[0].toString());
                        if (request.has("action") && !request.has("coordinate_space")) request.put("coordinate_space", "image_pixels");
                        String query = request.has("action") ? "MIAIPLUS:" + request : request.getString("goal");
                        Object service = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
                        Object result = Reflector.callMethod(service, "controlApp", query);
                        text = String.valueOf(Reflector.callMethod(result, "getText"));
                    } catch (Exception e) { text = "控屏停止：" + (e instanceof org.json.JSONException ? "缺少goal或session/action参数" : e.getMessage()); }
                    yield Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader), text, Map.of(), null, List.of(), false, null, false);
                    } finally { Hooks.leave(); }
                }
                case "toString" -> NAME;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Class<?> registry = Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader);
        Object existing = Reflector.getStaticObjectField(registry, "instance");
        if (existing != null) register(existing, tool);
        hooks.addAll(Hooks.hookAllConstructors(registry, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) { register(p.thisObject, tool); }
        }));
    }
    static void addDefinition(Context context, HookCallback.MethodHookParam p) {
        if (!registered || !available(context) || definition == null || !(p.args[1] instanceof List<?> old)) return;
        for (Object value : old) if (NAME.equals(Reflector.callMethod(value, "getName"))) return;
        var tools = new java.util.ArrayList<Object>(old); tools.add(definition); p.args[1] = tools;
    }
}
