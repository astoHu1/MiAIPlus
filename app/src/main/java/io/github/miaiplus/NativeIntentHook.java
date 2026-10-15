package io.github.miaiplus;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.net.URI;
import java.util.List;

/** Exact private intent endpoint only; unrelated HTTP and speech endpoints are untouched. */
public final class NativeIntentHook {
    private NativeIntentHook() {}
    public static void install(Context context, ClassLoader loader, List<XC_MethodHook.Unhook> hooks) throws Exception {
        Class<?> builder = XposedHelpers.findClass("io.ktor.client.request.HttpRequestBuilder", loader);
        Class<?> client = XposedHelpers.findClass("io.ktor.client.HttpClient", loader);
        Class<?> statement = XposedHelpers.findClass("io.ktor.client.statement.HttpStatement", loader);
        hooks.add(XposedBridge.hookMethod(statement.getDeclaredConstructor(builder, client), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Object request = p.args[0];
                Object url = XposedHelpers.callMethod(request, "getUrl");
                String value = String.valueOf(XposedHelpers.callMethod(url, "buildString"));
                if (!"/osbot/api/intent/v2/chat/completions".equals(URI.create(value).getPath())) return;
                try {
                    var config = NativeModelHook.config(context);
                    if (!config.getBoolean("globalModel")) return;
                    String endpoint = Endpoint.validate(config.getString("endpoint", ""));
                    String model = config.getString("model", "").trim();
                    if (model.isEmpty()) throw new IllegalStateException("请先配置全局模型名称");
                    Object body = XposedHelpers.callMethod(request, "getBody");
                    if (!(body instanceof String text)) throw new IllegalStateException("不支持的原生路由请求格式");
                    String adapted = IntentProtocol.request(text, model).toString();
                    // Validate and create the clean transport before mutating the request.
                    Object clean = NativeModelHook.cleanHttp(loader);
                    XposedHelpers.callStaticMethod(XposedHelpers.findClass("io.ktor.client.request.HttpRequestKt", loader),
                        "url", request, endpoint + "/chat/completions");
                    XposedHelpers.callMethod(XposedHelpers.callMethod(url, "getParameters"), "clear");
                    Object headers = XposedHelpers.callMethod(request, "getHeaders");
                    XposedHelpers.callMethod(headers, "clear");
                    XposedHelpers.callMethod(headers, "append", "Content-Type", "application/json");
                    String key = config.getString("apiKey", "");
                    if (!key.isBlank()) XposedHelpers.callMethod(headers, "append", "Authorization", "Bearer " + key);
                    XposedHelpers.callMethod(request, "setBody", adapted);
                    p.args[1] = clean;
                } catch (Throwable failure) {
                    p.setThrowable(new IllegalStateException("外部路由推理初始化失败", failure));
                }
            }
        }));
        Class<?> router = XposedHelpers.findClass("com.aios.osbot.router.t", loader);
        hooks.add(XposedHelpers.findAndHookMethod(router, "f", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (NativeModelHook.config(context).getBoolean("globalModel")) p.args[0] = IntentProtocol.response((String)p.args[0]);
                } catch (Exception invalid) { /* Native parser provides its existing null/fallback behavior. */ }
            }
        }));
    }
}
