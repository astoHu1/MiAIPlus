package io.github.miaiplus;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
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
    private static final ThreadLocal<Bundle> memorySettings = new ThreadLocal<>();
    private static final java.util.Map<Object, String> delegateSessions = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static long fetchedAt;
    static synchronized void invalidateConfig() { fetchedAt = 0; }
    static synchronized Bundle requestConfig(Context context) {
        // Endpoint switching must affect the next request, even inside the UI cache's two-second window.
        invalidateConfig();
        return config(context);
    }
    static synchronized Bundle config(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (modelConfig != null && now - fetchedAt < 2000) return modelConfig;
        // Fail closed: never silently send an explicitly external-model request to another provider.
        Bundle value = context.getContentResolver().call(BridgeProvider.URI, "model_config", null, null);
        if (value == null) throw new IllegalStateException("MiAIPlus 模型配置不可用");
        modelConfig = value; fetchedAt = now; return value;
    }
    public static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        NativePhoneUseTool.install(context, loader, hooks);
        NativeTurnLifecycle.install(context, loader, hooks);
        NativeIntentHook.install(context, loader, hooks);
        NativeReasoningHook.install(context, loader, hooks);
        NativeChatRoutingHook.install(context, loader, hooks);
        Class<?> message = Reflector.findClass("com.aios.osbot.agent.model.Message", loader);
        Class<?> image = Reflector.findClass("com.aios.osbot.agent.model.ImageContent", loader);
        var imageConstructor = image.getConstructor(String.class, String.class, String.class, int.class, int.class, Long.class);
        // Only the canonical constructor; a Kotlin synthetic constructor delegates here.
        for (var constructor : message.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 12 || constructor.getParameterTypes()[2] != String.class) continue;
            hooks.add(Hooks.hookMethod(constructor, new HookCallback() {
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
        Class<?> toolSuccess = Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader);
        var successConstructor = toolSuccess.getDeclaredConstructor(String.class, java.util.Map.class,
            List.class, List.class, boolean.class, String.class, boolean.class);
        hooks.add(Hooks.hookMethod(successConstructor, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.args[0] instanceof String text)) return;
                if (!text.contains("CONTINUE：控屏尚未完成。")) return;
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
        installTurnStop(context, loader, hooks, toolSuccess);
        Class<?> nativeConfig = Reflector.findClass("sm.a", loader);
        var configConstructor = configConstructor(loader);
        // Cover the dispatcher and every concrete OSBot transport, including callers
        // that construct OpenAI, Responses or Anthropic clients directly.
        Class<?> openai = Reflector.findClass("qm.i", loader);
        Class<?> ktor = Reflector.findClass("io.ktor.client.HttpClient", loader);
        var openaiConstructor = openai.getConstructor(String.class, String.class, ktor);
        hooks.add(Hooks.hookMethod(openai.getDeclaredMethod("e",
            Reflector.findClass("io.ktor.client.request.HttpRequestBuilder", loader), String.class, String.class,
            Reflector.findClass(HostCompatibility.choose("qm.f", "dn.f"), loader), String.class), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                String session = delegateSessions.get(p.thisObject);
                if (session == null || p.hasThrowable()) return;
                Object headers = Reflector.callMethod(p.args[0], "getHeaders");
                Reflector.callMethod(headers, "remove", ModelHeaders.SESSION);
                Reflector.callMethod(headers, "append", ModelHeaders.SESSION, session);
            }
        }));
        Method nativeStream = openai.getDeclaredMethod("streamCompletion", List.class, List.class, nativeConfig,
            Reflector.findClass("kotlin.coroutines.Continuation", loader));
        for (String className : new String[]{"qm.c", "um.h", "qm.i", "qm.j", "qm.b"}) {
            Class<?> client = Reflector.findClass(className, loader);
            Method stream = client.getDeclaredMethod("streamCompletion", List.class, List.class, nativeConfig,
                Reflector.findClass("kotlin.coroutines.Continuation", loader));
            hooks.add(Hooks.hookMethod(stream, new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (delegating.get()) return;
                    NativePhoneUseTool.addDefinition(context, param);
                    Bundle setting;
                    try { setting = requestConfig(context); }
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
                    Object delegate = openaiConstructor.newInstance(setting.getString("apiKey", ""), endpoint,
                        cleanHttp(loader, setting));
                    delegateSessions.put(delegate, ModelHeaders.nativeSession((List<?>)param.args[0]));
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
        Class<?> memory = Reflector.findClass("ep.j", loader);
        Method request = memory.getDeclaredMethod("o", nativeConfig, String.class, String.class, String.class,
            long.class, Reflector.findClass("dp.a", loader), Reflector.findClass("kotlin.coroutines.Continuation", loader));
        hooks.add(Hooks.hookMethod(request, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (p.args[0] == null) return; // Coroutine resume: request is already configured.
                try {
                Bundle setting = requestConfig(context);
                if (!setting.getBoolean("globalModel")) return;
                String endpoint = Endpoint.validate(setting.getString("endpoint", ""));
                String model = setting.getString("model", "").trim();
                if (model.isBlank()) { p.setThrowable(new IllegalStateException("请先配置全局模型名称")); return; }
                p.args[0] = replacement(configConstructor, loader, p.args[0], setting, endpoint, model);
                p.args[5] = null; // Xiaomi billing/feature query parameters do not apply externally.
                p.setObjectExtra("memoryTransportPrevious", memoryTransport.get());
                p.setObjectExtra("memorySettingsPrevious", memorySettings.get());
                setting = new Bundle(setting);
                String requestId = (String)p.args[3];
                setting.putString(ModelHeaders.SESSION, requestId == null || requestId.isBlank()
                    ? java.util.UUID.randomUUID().toString() : ModelHeaders.identifier("memory:" + requestId));
                memorySettings.set(setting);
                memoryTransport.set(true);
                } catch (Throwable failure) {
                    p.setThrowable(new IllegalStateException("记忆推理模型配置不可用", failure));
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object previous = p.getObjectExtra("memoryTransportPrevious");
                if (previous instanceof Boolean value) {
                    if (value) memoryTransport.set(true); else memoryTransport.remove();
                    Object oldSettings = p.getObjectExtra("memorySettingsPrevious");
                    if (oldSettings instanceof Bundle bundle) memorySettings.set(bundle); else memorySettings.remove();
                }
            }
        }));
        hooks.add(Reflector.findAndHookMethod("er.a0", loader, "getClient", new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (memoryTransport.get()) {
                    try { p.setResult(cleanHttp(loader, memorySettings.get())); }
                    catch (Throwable failure) { p.setThrowable(new IllegalStateException("外部记忆推理连接初始化失败", failure)); }
                }
            }
        }));
    }
    /** The pinned host consumes this decision for every result, including mixed parallel-tool batches. */
    private static void installTurnStop(Context app, ClassLoader loader, List<HookCallback.Unhook> hooks, Class<?> success)
        throws Exception {
        Class<?> failure = Reflector.findClass("com.aios.osbot.tools.ToolResult$Failure", loader);
        Class<?> context = Reflector.findClass("hj.e", loader);
        Class<?> continuation = Reflector.findClass("kotlin.coroutines.Continuation", loader);
        Object stop = Reflector.getStaticObjectField(Reflector.findClass("oj.b", loader), "STOP");
        Object stopAfterTool = Reflector.newInstance(Reflector.findClass("kj.i$b", loader), stop);
        Method gate = Reflector.findClass("kj.g", loader).getDeclaredMethod("evalShouldStopAfterTool", context,
            Reflector.findClass("com.aios.osbot.agent.model.ToolCall", loader),
            Reflector.findClass("com.aios.osbot.tools.ToolResult", loader), continuation);
        hooks.add(Hooks.hookMethod(gate, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                boolean failed = failure.isInstance(p.args[2]);
                if (!failed && !success.isInstance(p.args[2])) return;
                String text = (String)Reflector.callMethod(p.args[2], failed ? "getError" : "getResult");
                String name = (String)Reflector.callMethod(p.args[1], "getName");
                Object arguments = Reflector.callMethod(p.args[1], "getArguments");
                if (!ForegroundConsent.isPhoneTool(name, String.valueOf(arguments))) return;
                if (p.args[0] != null) NativeTurnLifecycle.observe(app,
                    Reflector.callMethod(p.args[0], "getExecutionContext"), text);
                if (ForegroundConsent.endsNativeToolTurn(name, String.valueOf(arguments), text)) p.setResult(stopAfterTool);
            }
        }));
    }
    private static Object replacement(java.lang.reflect.Constructor<?> configConstructor, ClassLoader loader,
        Object old, Bundle setting, String endpoint, String model) throws Exception {
        Object[] arguments = new Object[]{model,
                        ((Number)Reflector.callMethod(old, "getTemperature")).doubleValue(),
                        Reflector.callMethod(old, "getMaxTokens"),
                        ((Number)Reflector.callMethod(old, "getTopP")).doubleValue(),
                        endpoint, null, Reflector.callMethod(old, "getSystemPrompt"),
                        setting.getString("apiKey", ""), false, "openai", null, false,
                        Reflector.newInstance(Reflector.findClass("sm.f", loader)), null, false};
        if (HostCompatibility.modern()) {
            arguments = java.util.Arrays.copyOf(arguments, 16);
            arguments[15] = Reflector.callMethod(old, "getSendTopP");
        }
        return configConstructor.newInstance(arguments);
    }
    private static java.lang.reflect.Constructor<?> configConstructor(ClassLoader loader) throws Exception {
        Class<?>[] signature = new Class<?>[]{String.class, double.class, Integer.class, double.class,
            String.class, String.class, String.class, String.class, boolean.class, String.class, String.class,
            boolean.class, Reflector.findClass("sm.f", loader), Reflector.findClass("bk.a", loader), boolean.class};
        if (HostCompatibility.modern()) {
            signature = java.util.Arrays.copyOf(signature, 16);
            signature[15] = boolean.class;
        }
        return Reflector.findClass("sm.a", loader).getConstructor(signature);
    }
    static Object newConfig(ClassLoader loader, Object... legacyArguments) throws Exception {
        Object[] arguments = legacyArguments;
        if (HostCompatibility.modern()) {
            arguments = java.util.Arrays.copyOf(arguments, 16);
            arguments[15] = false;
        }
        return configConstructor(loader).newInstance(arguments);
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
    static boolean canReload() { return NativeResponsesAdapter.isIdle(); }
    static String memorySession() {
        Bundle setting = memorySettings.get();
        return memoryTransport.get() && setting != null ? setting.getString(ModelHeaders.SESSION) : null;
    }
    static synchronized void dispose() {
        for (Object http : externalHttp.values()) Reflector.callMethod(http, "close");
        externalHttp.clear(); modelConfig = null; fetchedAt = 0;
        delegating.remove(); memoryTransport.remove(); memorySettings.remove();
        NativeResponsesAdapter.clear();
        delegateSessions.clear(); ModelHeaders.clear();
    }
    private static final java.util.Map<String, Object> externalHttp = new java.util.HashMap<>();
    interface Configure { void apply(Object value) throws Throwable; }
    static Object function(ClassLoader loader, Configure block) {
        Class<?> function = Reflector.findClass("kotlin.jvm.functions.Function1", loader);
        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, args) -> {
            if (method.getName().equals("invoke")) {
                block.apply(args[0]);
                return Reflector.getStaticObjectField(Reflector.findClass("kotlin.Unit", loader), "INSTANCE");
            }
            if (method.getName().equals("toString")) return "MiAIPlusHttpConfiguration";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
    }
    static synchronized Object cleanHttp(ClassLoader loader, Bundle setting) {
        String api = setting.getString("api", "chat");
        String reasoning = ReasoningPolicy.normalize(setting.getString("model", ""), api, setting.getString("reasoning", "default"));
        String key = api + ":" + reasoning;
        if (!java.util.Set.of("chat", "responses").contains(api)) throw new IllegalArgumentException("未知模型 API 协议");
        if (externalHttp.containsKey(key)) return externalHttp.get(key);
        Object engine = Reflector.getStaticObjectField(Reflector.findClass("io.ktor.client.engine.okhttp.OkHttp", loader), "INSTANCE");
        Object configure = function(loader, client -> {
            Reflector.callMethod(client, "setFollowRedirects", false);
            Reflector.callMethod(client, "engine",
                function(loader, engineConfig -> NativeResponsesAdapter.install(loader, engineConfig, api, reasoning)));
            Object negotiation = Reflector.getStaticObjectField(Reflector.findClass("io.ktor.client.plugins.contentnegotiation.ContentNegotiation", loader), "Plugin");
            Reflector.callMethod(client, "install", negotiation, function(loader, plugin ->
                Reflector.callStaticMethod(Reflector.findClass("io.ktor.serialization.kotlinx.json.JsonSupportKt", loader),
                    "json$default", plugin, null, null, 3, null)));
            Object timeout = Reflector.getStaticObjectField(Reflector.findClass("io.ktor.client.plugins.HttpTimeout", loader), "Plugin");
            Reflector.callMethod(client, "install", timeout, function(loader, plugin -> {
                Reflector.callMethod(plugin, "setConnectTimeoutMillis", Long.valueOf(30000));
                Reflector.callMethod(plugin, "setSocketTimeoutMillis", Long.valueOf(120000));
                Reflector.callMethod(plugin, "setRequestTimeoutMillis", Long.valueOf(180000));
            }));
        });
        Object http = Reflector.callStaticMethod(Reflector.findClass("io.ktor.client.HttpClientKt", loader), "HttpClient", engine, configure);
        externalHttp.put(key, http);
        return http;
    }
}
