package io.github.miaiplus;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pinned native model integration: retain stock messages, streaming, history and tool execution. */
public final class NativeModelHook {
    private static final Pattern FRAME = Pattern.compile("MIAIPLUS_FRAME:([a-f0-9-]{36})");
    private static Bundle modelConfig;
    // The clean native OpenAI delegate also passes through its own hooked method.
    // This synchronous guard prevents rerouting that delegate back into itself.
    private static final ThreadLocal<Boolean> delegating = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> memoryTransport = ThreadLocal.withInitial(() -> false);
    private static long fetchedAt;
    static synchronized Bundle config(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (modelConfig != null && now - fetchedAt < 2000) return modelConfig;
        // Fail closed: never silently send an explicitly external-model request to another provider.
        Bundle value = context.getContentResolver().call(BridgeProvider.URI, "model_config", null, null);
        if (value == null) throw new IllegalStateException("MiAIPlus 模型配置不可用");
        modelConfig = value; fetchedAt = now; return value;
    }
    public static void install(Context context, ClassLoader loader, List<XC_MethodHook.Unhook> hooks) throws Exception {
        NativeComputerTool.install(context, loader, hooks);
        NativeIntentHook.install(context, loader, hooks);
        Class<?> message = XposedHelpers.findClass("com.aios.osbot.agent.model.Message", loader);
        Class<?> image = XposedHelpers.findClass("com.aios.osbot.agent.model.ImageContent", loader);
        var imageConstructor = image.getConstructor(String.class, String.class, String.class, int.class, int.class, Long.class);
        // Only the canonical constructor; a Kotlin synthetic constructor delegates here.
        for (var constructor : message.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 12 || constructor.getParameterTypes()[2] != String.class) continue;
            hooks.add(XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!(param.args[2] instanceof String content) || !String.valueOf(param.args[1]).equals("TOOL")) return;
                    var matcher = FRAME.matcher(content);
                    if (!matcher.find()) return;
                    String id = matcher.group(1);
                    Object nativeImage;
                    try { nativeImage = readImage(context, id, imageConstructor); }
                    catch (Exception e) { param.args[2] = matcher.replaceAll("[截图传输失败，请停止并让用户接管]"); return; }
                    List<Object> images = new ArrayList<>();
                    if (param.args[6] instanceof List<?> old) images.addAll(old);
                    images.add(nativeImage); param.args[6] = images;
                    param.args[2] = matcher.replaceAll("[本步截图已附加为原生图片]");
                }
            }));
        }
        // Native UI/CLI wrappers may mark app-tool output as a final direct answer.
        // Only our structured ongoing observations must be fed back into the agent loop.
        Class<?> toolSuccess = XposedHelpers.findClass("com.aios.osbot.tools.ToolResult$Success", loader);
        var successConstructor = toolSuccess.getDeclaredConstructor(String.class, java.util.Map.class,
            List.class, List.class, boolean.class, String.class, boolean.class);
        hooks.add(XposedBridge.hookMethod(successConstructor, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.args[0] instanceof String text) || !text.contains("CONTINUE：控屏尚未完成。")) return;
                var marker = FRAME.matcher(text);
                if (marker.find()) {
                    try {
                        Object nativeImage = readImage(context, marker.group(1), imageConstructor);
                        List<Object> images = new ArrayList<>();
                        if (param.args[2] instanceof List<?> old) images.addAll(old);
                        images.add(nativeImage); param.args[2] = images;
                        param.args[0] = marker.replaceAll("[本步截图已附加为原生图片]");
                    } catch (Exception unavailable) {
                        param.args[0] = marker.replaceAll("[截图不可用；仅可依据无障碍树，无法判断时接管]");
                    }
                }
                param.args[4] = false;
                if (param.args[1] instanceof java.util.Map<?, ?> metadata) {
                    var copy = new java.util.HashMap<>(metadata);
                    copy.remove("cli_internal_direct_answer"); param.args[1] = copy;
                }
            }
        }));
        Class<?> nativeConfig = XposedHelpers.findClass("sm.a", loader);
        var configConstructor = nativeConfig.getConstructor(String.class, double.class, Integer.class, double.class,
            String.class, String.class, String.class, String.class, boolean.class, String.class, String.class,
            boolean.class, XposedHelpers.findClass("sm.f", loader), XposedHelpers.findClass("bk.a", loader), boolean.class);
        // Cover the dispatcher and every concrete OSBot transport, including callers
        // that construct OpenAI, Responses or Anthropic clients directly.
        Class<?> openai = XposedHelpers.findClass("qm.i", loader);
        Class<?> ktor = XposedHelpers.findClass("io.ktor.client.HttpClient", loader);
        var openaiConstructor = openai.getConstructor(String.class, String.class, ktor);
        Method nativeStream = openai.getDeclaredMethod("streamCompletion", List.class, List.class, nativeConfig,
            XposedHelpers.findClass("kotlin.coroutines.Continuation", loader));
        for (String className : new String[]{"qm.c", "um.h", "qm.i", "qm.j", "qm.b"}) {
            Class<?> client = XposedHelpers.findClass(className, loader);
            Method stream = client.getDeclaredMethod("streamCompletion", List.class, List.class, nativeConfig,
                XposedHelpers.findClass("kotlin.coroutines.Continuation", loader));
            hooks.add(XposedBridge.hookMethod(stream, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (delegating.get()) return;
                    NativeComputerTool.addDefinition(context, param);
                    Bundle setting;
                    try { setting = config(context); }
                    catch (Exception unavailable) {
                        if (modelConfig != null && modelConfig.getBoolean("globalModel")) param.setThrowable(new IllegalStateException("外部模型配置服务不可用"));
                        return;
                    }
                    if (!setting.getBoolean("globalModel")) return;
                    try {
                    String endpoint = Endpoint.validate(setting.getString("endpoint", ""));
                    String model = setting.getString("model", "").trim();
                    if (model.isBlank()) throw new IllegalStateException("请先配置全局模型名称");
                    Object old = param.args[2];
                    Object replacement = replacement(configConstructor, loader, old, setting, endpoint, model);
                    // Keep native serialization/SSE, but never reuse Xiaomi's account-auth transport.
                    Object delegate = openaiConstructor.newInstance(setting.getString("apiKey", ""), endpoint, cleanHttp(loader));
                    delegating.set(true);
                    try { param.setResult(nativeStream.invoke(delegate, param.args[0], param.args[1], replacement, param.args[3])); }
                    finally { delegating.remove(); }
                    } catch (Throwable failure) { param.setThrowable(new IllegalStateException("外部主模型初始化失败，请检查配置或关闭全局替换", failure)); }
                }
            }));
        }
        // Claw memory summaries have a separate non-streaming OpenAI path.
        // Preserve its request/response, retry and cancellation code; replace only
        // model configuration and the HTTP getter during synchronous request setup.
        Class<?> memory = XposedHelpers.findClass("ep.j", loader);
        Method request = memory.getDeclaredMethod("o", nativeConfig, String.class, String.class, String.class,
            long.class, XposedHelpers.findClass("dp.a", loader), XposedHelpers.findClass("kotlin.coroutines.Continuation", loader));
        hooks.add(XposedBridge.hookMethod(request, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (p.args[0] == null) return; // Coroutine resume: request is already configured.
                try {
                Bundle setting = config(context);
                if (!setting.getBoolean("globalModel")) return;
                String endpoint = Endpoint.validate(setting.getString("endpoint", ""));
                String model = setting.getString("model", "").trim();
                if (model.isBlank()) { p.setThrowable(new IllegalStateException("请先配置全局模型名称")); return; }
                p.args[0] = replacement(configConstructor, loader, p.args[0], setting, endpoint, model);
                p.args[5] = null; // Xiaomi billing/feature query parameters do not apply externally.
                p.setObjectExtra("memoryTransportPrevious", memoryTransport.get());
                memoryTransport.set(true);
                } catch (Throwable failure) {
                    p.setThrowable(new IllegalStateException("记忆推理模型配置不可用", failure));
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object previous = p.getObjectExtra("memoryTransportPrevious");
                if (previous instanceof Boolean value) {
                    if (value) memoryTransport.set(true); else memoryTransport.remove();
                }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod("er.a0", loader, "getClient", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (memoryTransport.get()) {
                    try { p.setResult(cleanHttp(loader)); }
                    catch (Throwable failure) { p.setThrowable(new IllegalStateException("外部记忆推理连接初始化失败", failure)); }
                }
            }
        }));
    }
    private static Object replacement(java.lang.reflect.Constructor<?> configConstructor, ClassLoader loader,
        Object old, Bundle setting, String endpoint, String model) throws Exception {
        return configConstructor.newInstance(model,
                        ((Number)XposedHelpers.callMethod(old, "getTemperature")).doubleValue(),
                        XposedHelpers.callMethod(old, "getMaxTokens"),
                        ((Number)XposedHelpers.callMethod(old, "getTopP")).doubleValue(),
                        endpoint, null, XposedHelpers.callMethod(old, "getSystemPrompt"),
                        setting.getString("apiKey", ""), XposedHelpers.callMethod(old, "getEnableThinking"), "openai", null, false,
                        XposedHelpers.newInstance(XposedHelpers.findClass("sm.f", loader)), null, false);
    }
    private static Object readImage(Context context, String id, java.lang.reflect.Constructor<?> constructor) throws Exception {
        try (var in = context.getContentResolver().openInputStream(Uri.parse(BridgeProvider.URI + "/frame/" + id));
             var out = new java.io.ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("控屏截图不可用");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 3000000) throw new IllegalStateException("控屏截图过大");
                out.write(buffer, 0, n);
            }
            var frame = new org.json.JSONObject(out.toString(java.nio.charset.StandardCharsets.UTF_8));
            return constructor.newInstance(frame.getString("image"), null, "image/jpeg", frame.getInt("width"), frame.getInt("height"), null);
        }
    }
    private static Object externalHttp;
    private interface Configure { void apply(Object value) throws Throwable; }
    private static Object function(ClassLoader loader, Configure block) {
        Class<?> function = XposedHelpers.findClass("kotlin.jvm.functions.Function1", loader);
        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, args) -> {
            if (method.getName().equals("invoke")) {
                block.apply(args[0]);
                return XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.Unit", loader), "INSTANCE");
            }
            if (method.getName().equals("toString")) return "MiAIPlusHttpConfiguration";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
    }
    static synchronized Object cleanHttp(ClassLoader loader) {
        if (externalHttp != null) return externalHttp;
        Object engine = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("io.ktor.client.engine.okhttp.OkHttp", loader), "INSTANCE");
        Object configure = function(loader, client -> {
            XposedHelpers.callMethod(client, "setFollowRedirects", false);
            Object negotiation = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("io.ktor.client.plugins.contentnegotiation.ContentNegotiation", loader), "Plugin");
            XposedHelpers.callMethod(client, "install", negotiation, function(loader, plugin ->
                XposedHelpers.callStaticMethod(XposedHelpers.findClass("io.ktor.serialization.kotlinx.json.JsonSupportKt", loader),
                    "json$default", plugin, null, null, 3, null)));
            Object timeout = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("io.ktor.client.plugins.HttpTimeout", loader), "Plugin");
            XposedHelpers.callMethod(client, "install", timeout, function(loader, plugin -> {
                XposedHelpers.callMethod(plugin, "setConnectTimeoutMillis", Long.valueOf(30000));
                XposedHelpers.callMethod(plugin, "setSocketTimeoutMillis", Long.valueOf(120000));
                XposedHelpers.callMethod(plugin, "setRequestTimeoutMillis", Long.valueOf(180000));
            }));
        });
        externalHttp = XposedHelpers.callStaticMethod(XposedHelpers.findClass("io.ktor.client.HttpClientKt", loader), "HttpClient", engine, configure);
        return externalHttp;
    }
}
