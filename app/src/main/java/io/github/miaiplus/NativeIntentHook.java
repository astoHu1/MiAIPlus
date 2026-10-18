package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.net.URI;
import java.util.List;

/** Exact private intent endpoint only; unrelated HTTP and speech endpoints are untouched. */
public final class NativeIntentHook {
    private NativeIntentHook() {}
    public static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> builder = Reflector.findClass("io.ktor.client.request.HttpRequestBuilder", loader);
        Class<?> client = Reflector.findClass("io.ktor.client.HttpClient", loader);
        Class<?> statement = Reflector.findClass("io.ktor.client.statement.HttpStatement", loader);
        hooks.add(Hooks.hookMethod(statement.getDeclaredConstructor(builder, client), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Object request = p.args[0];
                Object url = Reflector.callMethod(request, "getUrl");
                String value = String.valueOf(Reflector.callMethod(url, "buildString"));
                String memorySession = NativeModelHook.memorySession();
                if (memorySession != null) {
                    Object headers = Reflector.callMethod(request, "getHeaders");
                    Reflector.callMethod(headers, "remove", ModelHeaders.SESSION);
                    Reflector.callMethod(headers, "append", ModelHeaders.SESSION, memorySession);
                }
                if (!"/osbot/api/intent/v2/chat/completions".equals(URI.create(value).getPath())) return;
                try {
                    var config = NativeModelHook.requestConfig(context);
                    if (!config.getBoolean("globalModel")) return;
                    String endpoint = Endpoint.validate(config.getString("endpoint", ""));
                    String model = config.getString("model", "").trim();
                    if (model.isEmpty()) throw new IllegalStateException("请先配置全局模型名称");
                    Object body = Reflector.callMethod(request, "getBody");
                    if (!(body instanceof String text)) throw new IllegalStateException("不支持的原生路由请求格式");
                    String adapted = IntentProtocol.request(text, model).toString();
                    // Validate and create the clean transport before mutating the request.
                    Object clean = NativeModelHook.cleanHttp(loader, config);
                    Reflector.callStaticMethod(Reflector.findClass("io.ktor.client.request.HttpRequestKt", loader),
                        "url", request, endpoint + "/chat/completions");
                    Reflector.callMethod(Reflector.callMethod(url, "getParameters"), "clear");
                    Object headers = Reflector.callMethod(request, "getHeaders");
                    Reflector.callMethod(headers, "clear");
                    Reflector.callMethod(headers, "append", "Content-Type", "application/json");
                    String requestId = new org.json.JSONObject(text).optString("rid", "");
                    Reflector.callMethod(headers, "append", ModelHeaders.SESSION, requestId.isBlank()
                        ? java.util.UUID.randomUUID().toString() : ModelHeaders.identifier("intent:" + requestId));
                    String key = config.getString("apiKey", "");
                    if (!key.isBlank()) Reflector.callMethod(headers, "append", "Authorization", "Bearer " + key);
                    Reflector.callMethod(request, "setBody", adapted);
                    p.args[1] = clean;
                } catch (Throwable failure) {
                    p.setThrowable(new IllegalStateException("外部路由推理初始化失败", failure));
                }
            }
        }));
        Class<?> router = Reflector.findClass("com.aios.osbot.router.t", loader);
        hooks.add(Reflector.findAndHookMethod(router, "f", String.class, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (NativeModelHook.config(context).getBoolean("globalModel")) p.args[0] = IntentProtocol.response((String)p.args[0]);
                } catch (Exception invalid) { /* Native parser provides its existing null/fallback behavior. */ }
            }
        }));
    }
}
