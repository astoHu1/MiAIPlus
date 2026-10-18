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
    public static final String NAME = PhoneUseRouting.FOREGROUND_TOOL;
    public static final String BACKGROUND_NAME = PhoneUseRouting.BACKGROUND_TOOL;
    private static final String DESCRIPTION = "本机手机控屏。start时传goal取得session和观察；然后每次传session及一个action对象，按新观察继续调用直到finish。剩余动作预算为0时只能finish/take_over/ask_user。STOP、PAUSE或错误表示本轮结束，不得重试或重新提交goal；向用户如实说明未完成和原因。无需额外API，由你逐步规划。默认使用截图JPEG像素坐标(image_pixels)，不是0..1000；也可显式选screen_pixels或normalized，必须匹配观察尺寸。不要复述CONTINUE或用自然语言替代action。系统专用工具优先。";
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
    private static final java.util.Map<String, Object> definitions = new java.util.LinkedHashMap<>();
    private static String description(String name) {
        return (NAME.equals(name) ? "前台控屏：直接操作用户当前主屏。" : "后台控屏：在独立虚拟屏操作应用，尽量不打扰用户主屏。")
            + "根据用户要求选择此工具；未指定后台时使用前台工具。整个会话继续使用同一个工具，包括后台经用户同意切换前台以后。" + DESCRIPTION;
    }
    private static volatile boolean registered;
    private static final java.util.Set<Object> registries = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static void register(Object registry, Object tool) {
        Reflector.callMethod(registry, HostCompatibility.choose("registerSystem", "register"), tool);
        synchronized (registries) { registries.add(registry); }
        registered = true;
    }
    static void dispose() {
        synchronized (registries) {
            for (Object registry : registries) {
                for (String name : new String[]{NAME, BACKGROUND_NAME}) {
                    Reflector.callMethod(registry, "unregister", name);
                    if (!HostCompatibility.modern())
                        ((java.util.Set<?>)Reflector.getObjectField(registry, "systemToolNames")).remove(name);
                }
            }
            registries.clear();
        }
        registered = false; definitions.clear();
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
        for (String name : new String[]{NAME, BACKGROUND_NAME}) installTool(context, loader, hooks, schema, name);
    }
    private static void installTool(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks, Object schema, String name) {
        String description = description(name);
        definitions.put(name, Reflector.newInstance(Reflector.findClass("com.aios.osbot.llm.model.ToolDefinition", loader), name, description, schema));
        Class<?> toolType = Reflector.findClass("com.aios.osbot.tools.Tool", loader);
        Object tool = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{toolType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getName" -> name;
                case "getDisplayName" -> NAME.equals(name) ? "前台控屏" : "后台控屏";
                case "getDescription", "getSummary", "getSearchHint" -> description;
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
                        String query = PhoneUseRouting.encode(name, request);
                        Object service = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
                        try (var origin = ConversationReturn.bind(args[1])) {
                            Object result = Reflector.callMethod(service, "controlApp", query);
                            text = String.valueOf(Reflector.callMethod(result, "getText"));
                            NativeTurnLifecycle.observe(context, args[1], text);
                            if (Boolean.TRUE.equals(Reflector.callMethod(result, "isError")))
                                yield failure(loader, text);
                        }
                    } catch (Exception e) {
                        yield failure(loader, e instanceof org.json.JSONException ? "缺少goal或session/action参数" : "控屏工具调用失败");
                    }
                    boolean terminal = ForegroundConsent.endsNativeTurn(text);
                    yield Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader), text,
                        terminal ? Map.of("cli_internal_direct_answer", true) : Map.of(), null, List.of(), terminal, null, false);
                    } finally { Hooks.leave(); }
                }
                case "toString" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Class<?> registry = Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader);
        if (HostCompatibility.modern()) {
            // 8.3.30 uses a DI-owned registry, with no static instance or registerSystem.
            // Reads also cover existing registries after hot reload, and repopulate after evict().
            for (var method : registry.getDeclaredMethods()) {
                if (!java.util.Set.of("get", "getAll", "peek", "peekAll", "searchTools").contains(method.getName())) continue;
                hooks.add(Hooks.hookMethod(method, new HookCallback() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { register(p.thisObject, tool); }
                }));
            }
            registered = true;
        } else {
            Object existing = Reflector.getStaticObjectField(registry, "instance");
            if (existing != null) register(existing, tool);
        }
        hooks.addAll(Hooks.hookAllConstructors(registry, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) { register(p.thisObject, tool); }
        }));
    }
    private static Object failure(ClassLoader loader, String reason) {
        String text = PhoneUseResult.stopped(reason);
        Object permanent = Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.tools.FailureType", loader), "PERMANENT");
        return Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Failure", loader),
            text, false, permanent, null, true, text, null);
    }
    static Object registry(Context context, ClassLoader loader) {
        if (!HostCompatibility.modern())
            return Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader), "instance");
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("com.aios.osbot.tools.phone.service.InCallServiceEntryPoint", loader));
        return Reflector.callMethod(entry, "toolRegistry");
    }
    static void addDefinition(Context context, HookCallback.MethodHookParam p) {
        if (!registered || !available(context) || definitions.isEmpty() || !(p.args[1] instanceof List<?> old)) return;
        var tools = new java.util.ArrayList<Object>(old);
        for (var entry : definitions.entrySet()) {
            boolean exists = false;
            for (Object value : old) if (entry.getKey().equals(Reflector.callMethod(value, "getName"))) { exists = true; break; }
            if (!exists) tools.add(entry.getValue());
        }
        p.args[1] = tools;
    }
}
