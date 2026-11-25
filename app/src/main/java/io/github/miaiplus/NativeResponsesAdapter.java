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
    private final ReasoningPolicy.Plan policy;
    // Bounded opaque output replay, indexed by the exact model + tool call group in native history.
    // Share replay across effort-specific clients so changing intensity retains tool reasoning.
    private static final Map<String, String> outputs = new LinkedHashMap<>();
    private static int cachedCharacters;
    private static final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    static boolean isIdle() { return requests.get() == 0; }
    static synchronized void clear() { outputs.clear(); cachedCharacters = 0; }
    private NativeResponsesAdapter(ClassLoader loader, String api, String reasoning, ReasoningPolicy.Plan policy) {
        this.loader = loader; this.api = api; this.reasoning = reasoning; this.policy = policy;
    }

    static void install(ClassLoader loader, Object engineConfig, String api, String reasoning, ReasoningPolicy.Plan policy) {
        Object interceptor = interceptor(loader, api, reasoning, policy);
        Reflector.callMethod(engineConfig, "config", NativeModelHook.function(loader, builder -> {
            Reflector.callMethod(builder, "followRedirects", false);
            Reflector.callMethod(builder, "followSslRedirects", false);
            Reflector.callMethod(builder, "addInterceptor", interceptor);
        }));
    }
    static Object interceptor(ClassLoader loader, String api, String reasoning) {
        return interceptor(loader, api, reasoning, null);
    }
    static Object interceptor(ClassLoader loader, String api, String reasoning, ReasoningPolicy.Plan policy) {
        NativeResponsesAdapter adapter = new NativeResponsesAdapter(loader, api, reasoning, policy);
        return Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("gg2.w", loader)}, (proxy, method, args) -> {
            if (method.getName().equals("intercept")) {
                return TransportBoundary.execute(() -> {
                    io.github.miaiplus.hook.Hooks.enter();
                    requests.incrementAndGet();
                    try { return adapter.intercept(args[0]); }
                    catch (Throwable failure) {
                        // The boundary below deliberately hides the cause from XiaoAi; keep its shape here.
                        PhoneUseLog.failure("native_transport", failure, "api", adapter.api);
                        throw failure;
                    }
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
        boolean anthropic = "anthropic".equals(api);
        boolean convert = anthropic || "responses".equals(api);
        String replayIdentity = api + "\n" + url + "\n" + model;
        if (convert) restore(chat, replayIdentity, anthropic);
        JSONObject body = switch (api) {
            case "responses" -> ModelProtocol.request(chat);
            case "anthropic" -> AnthropicProtocol.request(chat);
            default -> chat;
        };
        (policy == null ? ReasoningPolicy.builtin(model, api) : policy).apply(body, model, api, reasoning);
        String serialized = body.toString();
        PhoneUseLog.request(body, api, serialized);
        Object builder = Reflector.callMethod(request, "newBuilder");
        Reflector.callMethod(builder, "header", "User-Agent", ModelHeaders.USER_AGENT);
        Object session = Reflector.callMethod(request, "header", ModelHeaders.SESSION);
        if (!(session instanceof String value) || value.isBlank())
            Reflector.callMethod(builder, "header", ModelHeaders.SESSION, java.util.UUID.randomUUID().toString());
        if (convert) {
            String endpoint = url.substring(0, url.length() - "/chat/completions".length());
            Reflector.callMethod(builder, "url", ModelProtocol.url(endpoint, api));
        }
        if (anthropic) {
            String authorization = (String)Reflector.callMethod(request, "header", "Authorization");
            String key = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
            Reflector.callMethod(builder, "removeHeader", "Authorization");
            Reflector.callMethod(builder, "removeHeader", "x-api-key");
            for (var header : ModelHeaders.provider(api, key).entrySet())
                Reflector.callMethod(builder, "header", header.getKey(), header.getValue());
        }
        Object requestBody = Reflector.callStaticMethod(type("gg2.g0"), "create", jsonType(), serialized);
        Reflector.callMethod(builder, "method", "POST", requestBody);
        Reflector.callMethod(builder, "removeHeader", "Content-Length");
        long sent = android.os.SystemClock.elapsedRealtime();
        Object response = Reflector.callMethod(chain, "proceed", Reflector.callMethod(builder, "build"));
        try {
            // Headers received; a streamed body follows. A non-2xx status is all XiaoAi turns into its own sentence.
            PhoneUseLog.event("model_http_response", "code", Reflector.callMethod(response, "code"), "api", api,
                "transport", "native_external", "stream", body.optBoolean("stream"),
                "durationMs", android.os.SystemClock.elapsedRealtime() - sent);
        } catch (RuntimeException unavailable) { PhoneUseLog.failure("native_transport_status", unavailable); }
        if (!convert || !Boolean.TRUE.equals(Reflector.callMethod(response, "isSuccessful")))
            return trackResponse(response);
        Object originalBody = Reflector.callMethod(response, "body");
        InputStream pending = null;
        try {
            InputStream input = (InputStream)Reflector.callMethod(originalBody, "byteStream");
            Object replacement;
            if (body.optBoolean("stream")) {
                InputStream translated = track(anthropic
                    ? new AnthropicStream(input, result -> remember(replayIdentity, result, true))
                    : new ResponsesStream(input, result -> remember(replayIdentity, result, false)));
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
                        if (out.size() + count > 2_000_000) throw new java.io.IOException("模型响应过大");
                        out.write(bytes, 0, count);
                    }
                    result = new JSONObject(out.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
                }
                JSONObject completion = anthropic ? AnthropicProtocol.completion(result) : ModelProtocol.completion(result);
                remember(replayIdentity, result, anthropic);
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
    private static String key(String identity, JSONArray calls, boolean nativeCalls, boolean anthropic) throws Exception {
        JSONArray ids = new JSONArray().put(identity);
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i);
            if (!nativeCalls && !(anthropic ? "tool_use" : "function_call").equals(call.optString("type"))) continue;
            JSONObject fn = nativeCalls ? call.getJSONObject("function") : call;
            JSONObject arguments = !nativeCalls && anthropic ? fn.getJSONObject("input")
                : new JSONObject(fn.getString("arguments"));
            ids.put(new JSONArray().put(call.getString(nativeCalls || anthropic ? "id" : "call_id"))
                .put(fn.getString("name")).put(arguments.toString()));
        }
        return ids.length() == 1 ? null : ids.toString();
    }
    private static synchronized void remember(String identity, JSONObject response, boolean anthropic) {
        try {
            JSONArray output = response.getJSONArray(anthropic ? "content" : "output");
            String key = key(identity, output, false, anthropic), value = output.toString();
            if (key == null || key.length() + value.length() > 1_000_000) return;
            String previous = outputs.put(key, value);
            cachedCharacters += value.length() - (previous == null ? 0 : previous.length()) + (previous == null ? key.length() : 0);
            while (outputs.size() > 64 || cachedCharacters > 2_000_000) {
                String oldest = outputs.keySet().iterator().next();
                cachedCharacters -= oldest.length() + outputs.remove(oldest).length();
            }
        } catch (Exception invalid) { /* Missing optional replay must not alter a completed response. */ }
    }
    private static synchronized void restore(JSONObject chat, String identity, boolean anthropic) throws Exception {
        JSONArray messages = chat.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || !"assistant".equals(message.optString("role"))) continue;
            String value = outputs.get(key(identity, calls, true, anthropic));
            if (value != null) message.put(anthropic ? AnthropicProtocol.HISTORY : "_responses_output", new JSONArray(value));
        }
    }
}
