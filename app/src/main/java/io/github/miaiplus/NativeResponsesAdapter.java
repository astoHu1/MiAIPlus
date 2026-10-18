package io.github.miaiplus;

import io.github.miaiplus.hook.Reflector;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs only on the module's isolated native OkHttp client, never Xiaomi's account transport. */
final class NativeResponsesAdapter {
    private final ClassLoader loader;
    private final String api, reasoning;
    // Bounded opaque output replay, indexed by the exact model + tool call group in native history.
    // Share replay across effort-specific clients so changing intensity retains tool reasoning.
    private static final Map<String, String> outputs = new LinkedHashMap<>();
    private static int cachedCharacters;
    private static final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    static boolean isIdle() { return requests.get() == 0; }
    static synchronized void clear() { outputs.clear(); cachedCharacters = 0; }
    private NativeResponsesAdapter(ClassLoader loader, String api, String reasoning) {
        this.loader = loader; this.api = api; this.reasoning = reasoning;
    }

    static void install(ClassLoader loader, Object engineConfig, String api, String reasoning) {
        Object interceptor = interceptor(loader, api, reasoning);
        Reflector.callMethod(engineConfig, "config", NativeModelHook.function(loader, builder -> {
            Reflector.callMethod(builder, "followRedirects", false);
            Reflector.callMethod(builder, "followSslRedirects", false);
            Reflector.callMethod(builder, "addInterceptor", interceptor);
        }));
    }
    static Object interceptor(ClassLoader loader, String api, String reasoning) {
        NativeResponsesAdapter adapter = new NativeResponsesAdapter(loader, api, reasoning);
        return Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("gg2.w", loader)}, (proxy, method, args) -> {
            if (method.getName().equals("intercept")) {
                return TransportBoundary.execute(() -> {
                    io.github.miaiplus.hook.Hooks.enter();
                    requests.incrementAndGet();
                    try { return adapter.intercept(args[0]); }
                    finally { requests.decrementAndGet(); io.github.miaiplus.hook.Hooks.leave(); }
                });
            }
            if (method.getName().equals("toString")) return "MiAIPlusResponses";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
    }
    // Obfuscated OkHttp/Okio names in the version-pinned XiaoAI APK.
    private Class<?> type(String name) { return Reflector.findClass(name, loader); }
    private Object jsonType() { return Reflector.callStaticMethod(type("gg2.z"), "parse", "application/json; charset=utf-8"); }
    private Object intercept(Object chain) throws Throwable {
        Object request = Reflector.callMethod(chain, "request");
        String url = Reflector.callMethod(request, "url").toString();
        if (!url.endsWith("/chat/completions")) return Reflector.callMethod(chain, "proceed", request);
        Object buffer = Reflector.newInstance(type("zg2.l"));
        Reflector.callMethod(Reflector.callMethod(request, "body"), "writeTo", buffer);
        JSONObject chat = new JSONObject((String)Reflector.callMethod(buffer, "readUtf8"));
        String model = chat.getString("model");
        String replayIdentity = url + "\n" + model;
        if ("responses".equals(api)) restore(chat, replayIdentity);
        JSONObject body = "responses".equals(api) ? ModelProtocol.request(chat) : chat;
        ReasoningPolicy.apply(body, model, api, reasoning);
        Object builder = Reflector.callMethod(request, "newBuilder");
        Reflector.callMethod(builder, "header", "User-Agent", ModelHeaders.USER_AGENT);
        Object session = Reflector.callMethod(request, "header", ModelHeaders.SESSION);
        if (!(session instanceof String value) || value.isBlank())
            Reflector.callMethod(builder, "header", ModelHeaders.SESSION, java.util.UUID.randomUUID().toString());
        if ("responses".equals(api))
            Reflector.callMethod(builder, "url", url.substring(0, url.length() - "/chat/completions".length()) + "/responses");
        Object requestBody = Reflector.callStaticMethod(type("gg2.g0"), "create", jsonType(), body.toString());
        Reflector.callMethod(builder, "method", "POST", requestBody);
        Reflector.callMethod(builder, "removeHeader", "Content-Length");
        Object response = Reflector.callMethod(chain, "proceed", Reflector.callMethod(builder, "build"));
        if (!"responses".equals(api) || !Boolean.TRUE.equals(Reflector.callMethod(response, "isSuccessful")))
            return trackResponse(response);
        Object originalBody = Reflector.callMethod(response, "body");
        InputStream pending = null;
        try {
            InputStream input = (InputStream)Reflector.callMethod(originalBody, "byteStream");
            Object replacement;
            if (body.optBoolean("stream")) {
                InputStream translated = track(new ResponsesStream(input, result -> remember(replayIdentity, result)));
                pending = translated;
                Object source = Reflector.callStaticMethod(type("zg2.l0"), "source", translated);
                Object buffered = Reflector.callStaticMethod(type("zg2.l0"), "buffer", source);
                Object media = Reflector.callStaticMethod(type("gg2.z"), "parse", "text/event-stream");
                replacement = Reflector.callStaticMethod(type("gg2.i0"), "create", media, -1L, buffered);
            } else {
                JSONObject result;
                try (input; var out = new java.io.ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) != -1) {
                        if (out.size() + count > 2_000_000) throw new java.io.IOException("Responses 响应过大");
                        out.write(bytes, 0, count);
                    }
                    result = new JSONObject(out.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
                }
                JSONObject completion = ModelProtocol.completion(result);
                remember(replayIdentity, result);
                replacement = Reflector.callStaticMethod(type("gg2.i0"), "create", jsonType(), completion.toString());
            }
            Object responseBuilder = Reflector.callMethod(response, "newBuilder");
            Reflector.callMethod(responseBuilder, "body", replacement);
            Reflector.callMethod(responseBuilder, "removeHeader", "Content-Length");
            Reflector.callMethod(responseBuilder, "removeHeader", "Content-Encoding");
            Reflector.callMethod(responseBuilder, "header", "Content-Type", body.optBoolean("stream") ? "text/event-stream" : "application/json");
            return Reflector.callMethod(responseBuilder, "build");
        } catch (Throwable failure) {
            if (pending != null) try { pending.close(); } catch (java.io.IOException ignored) { }
            try { Reflector.callMethod(originalBody, "close"); }
            catch (Throwable ignored) { /* Preserve the original transport/conversion failure. */ }
            throw failure;
        }
    }
    private static InputStream track(InputStream input) {
        requests.incrementAndGet();
        return new java.io.FilterInputStream(input) {
            private boolean released;
            private synchronized void release() { if (!released) { released = true; requests.decrementAndGet(); } }
            @Override public int read() throws java.io.IOException {
                try { int value = in.read(); if (value == -1) release(); return value; }
                catch (Throwable failure) { release(); throw failure; }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                try { int count = in.read(bytes, offset, length); if (count == -1) release(); return count; }
                catch (Throwable failure) { release(); throw failure; }
            }
            @Override public void close() throws java.io.IOException { try { super.close(); } finally { release(); } }
        };
    }
    private Object trackResponse(Object response) throws Throwable {
        Object body = Reflector.callMethod(response, "body");
        if (body == null) return response;
        InputStream input = track((InputStream)Reflector.callMethod(body, "byteStream"));
        try {
            Object source = Reflector.callStaticMethod(type("zg2.l0"), "source", input);
            Object buffered = Reflector.callStaticMethod(type("zg2.l0"), "buffer", source);
            Object replacement = Reflector.callStaticMethod(type("gg2.i0"), "create",
                Reflector.callMethod(body, "contentType"), Reflector.callMethod(body, "contentLength"), buffered);
            Object builder = Reflector.callMethod(response, "newBuilder");
            Reflector.callMethod(builder, "body", replacement);
            return Reflector.callMethod(builder, "build");
        } catch (Throwable failure) { input.close(); throw failure; }
    }
    private static String key(String model, JSONArray calls, boolean nativeCalls) throws Exception {
        JSONArray ids = new JSONArray().put(model);
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i);
            if (nativeCalls || "function_call".equals(call.optString("type"))) {
                JSONObject fn = nativeCalls ? call.getJSONObject("function") : call;
                ids.put(new JSONArray().put(call.getString(nativeCalls ? "id" : "call_id"))
                    .put(fn.getString("name")).put(new JSONObject(fn.getString("arguments")).toString()));
            }
        }
        return ids.length() == 1 ? null : ids.toString();
    }
    private static synchronized void remember(String model, JSONObject response) {
        try {
            JSONArray output = response.getJSONArray("output");
            String key = key(model, output, false), value = output.toString();
            if (key == null || key.length() + value.length() > 1_000_000) return;
            String previous = outputs.put(key, value);
            cachedCharacters += value.length() - (previous == null ? 0 : previous.length()) + (previous == null ? key.length() : 0);
            while (outputs.size() > 64 || cachedCharacters > 2_000_000) {
                String oldest = outputs.keySet().iterator().next();
                cachedCharacters -= oldest.length() + outputs.remove(oldest).length();
            }
        } catch (Exception invalid) { /* Missing optional replay must not alter a completed response. */ }
    }
    private static synchronized void restore(JSONObject chat, String model) throws Exception {
        JSONArray messages = chat.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || !"assistant".equals(message.optString("role"))) continue;
            String value = outputs.get(key(model, calls, true));
            if (value != null) message.put("_responses_output", new JSONArray(value));
        }
    }
}
