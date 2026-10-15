package io.github.miaiplus;

import android.content.*;
import android.os.Bundle;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Debug APK only. Exercises native constructors, real hooks and native SSE client in the scoped process. */
public final class HookSmokeProbe {
    private static volatile Object dynClient;
    private static volatile java.lang.ref.WeakReference<Object> chatCallback = new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<android.view.View> chatInput = new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<android.view.View> functionBar = new java.lang.ref.WeakReference<>(null);
    private static BroadcastReceiver receiver;
    private static Context receiverContext;
    private static final java.util.Set<Thread> workers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static void dispose(Context context) {
        if (!workers.isEmpty()) throw new IllegalStateException("Debug probe is running");
        if (receiver != null) { receiverContext.unregisterReceiver(receiver); receiver = null; receiverContext = null; }
        dynClient = null; chatCallback.clear(); chatInput.clear(); functionBar.clear();
    }
    public static void install(Context context, ClassLoader loader) {
        io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass("ll1.f0", loader), new io.github.miaiplus.hook.HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) { chatCallback = new java.lang.ref.WeakReference<>(p.thisObject); }
        });
        Reflector.findAndHookMethod("com.xiaomi.voiceassistant.mainui.voicebar.StandardEditInputView", loader,
            "onAttachedToWindow", new io.github.miaiplus.hook.HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) { chatInput = new java.lang.ref.WeakReference<>((android.view.View)p.thisObject); }
            });
        Reflector.findAndHookMethod("com.xiaomi.voiceassistant.mainui.functionbar.FunctionBarView", loader,
            "onAttachedToWindow", new io.github.miaiplus.hook.HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) { functionBar = new java.lang.ref.WeakReference<>((android.view.View)p.thisObject); }
            });
        io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass("qm.c", loader), new io.github.miaiplus.hook.HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) { dynClient = p.thisObject; }
        });
        for (String name : new String[]{"qm.c", "um.h", "com.xiaomi.voiceassistant.UIAgentServiceForOSBot", "as.a"}) {
            Class<?> type = Reflector.findClass(name, loader);
            for (var method : type.getDeclaredMethods()) {
                if (!Set.of("streamCompletion", "controlApp", "build", "buildSessionHead", "buildTurnInject").contains(method.getName())) continue;
                io.github.miaiplus.hook.Hooks.hookMethod(method, new io.github.miaiplus.hook.HookCallback(10000) {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        io.github.miaiplus.hook.Hooks.log("MiAIPlus LIVE: " + name + "." + method.getName()
                            + (method.getName().equals("controlApp") ? " step=" + (p.args[0] instanceof String q && q.startsWith("MIAIPLUS:")) : ""));
                    }
                });
            }
        }
        for (String name : new String[]{"com.aios.osbot.tools.ToolResult$Success", "com.aios.osbot.agent.model.Message"}) {
            io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass(name, loader), new io.github.miaiplus.hook.HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (name.endsWith("Success")) {
                        String text = String.valueOf(invoke(p.thisObject, "getResult"));
                        if (text.contains("CONTINUE：控屏尚未完成。")) io.github.miaiplus.hook.Hooks.log("MiAIPlus LIVE: observation direct=" + invoke(p.thisObject, "isDirectAnswer"));
                    } else {
                        String text = String.valueOf(invoke(p.thisObject, "getContent"));
                        if (text.contains("CONTINUE：控屏尚未完成。")) io.github.miaiplus.hook.Hooks.log("MiAIPlus LIVE: observation Message role=" + invoke(p.thisObject, "getRole") + " images=" + (invoke(p.thisObject, "getImages") != null));
                    }
                }
            });
        }
        if (!android.app.Application.getProcessName().equals("com.miui.voiceassist")) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                try { io.github.miaiplus.hook.Hooks.enter(); }
                catch (IllegalStateException retiring) { return; }
                var pending = goAsync();
                Thread worker = new Thread(() -> {
                    String report;
                    try {
                        String token = intent.getStringExtra("frame");
                        report = "MIAIPLUS_FOCUS_UI".equals(token) ? focusUi(context, loader)
                            : "MIAIPLUS_TRANSPORT_FAILURE".equals(token) ? transportFailures(loader)
                            : "MIAIPLUS_STOCK_REASONING".equals(token) ? reasoningUi(context, loader, true) : run(context, loader, token);
                    }
                    catch (Throwable e) { report = "PROBE FAIL: " + e; io.github.miaiplus.hook.Hooks.log(e); }
                    try { Bundle b = new Bundle(); b.putString("report", report); context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, b); }
                    finally { workers.remove(Thread.currentThread()); pending.finish(); io.github.miaiplus.hook.Hooks.leave(); }
                }, "miaiplus-debug-probe");
                workers.add(worker); worker.start();
            }
        };
        // Application.attach changes the base ContextImpl's outer Context after this hook.
        // Keep a distinct ContextImpl whose receiver-dispatcher identity remains stable.
        receiverContext = context.createConfigurationContext(context.getResources().getConfiguration());
        receiverContext.registerReceiver(receiver, new IntentFilter("io.github.miaiplus.DEBUG_PROBE"), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
    }
    private static android.app.Activity resumedActivity() {
        Object thread = Reflector.callStaticMethod(Reflector.findClass("android.app.ActivityThread", null), "currentActivityThread");
        var records = (Map<?, ?>)Reflector.getObjectField(thread, "mActivities");
        for (Object record : records.values())
            if (!Boolean.TRUE.equals(Reflector.getObjectField(record, "paused"))) return (android.app.Activity)Reflector.getObjectField(record, "activity");
        return null;
    }
    private static String focusUi(Context context, ClassLoader loader) throws Exception {
        if (context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) throw new AssertionError("unlock device for focus UI test");
        android.app.Activity origin = MainThread.call(HookSmokeProbe::resumedActivity);
        Object manager = MainThread.call(() -> invoke(Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context), "getFloatManager"));
        android.view.View root = MainThread.call(() -> (android.view.View)invoke(manager, "getRootLayout"));
        boolean compact = MainThread.call(() -> root != null && root.isAttachedToWindow() && root.isShown()
            && root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams params
            && params.type >= android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW);
        if (!compact && origin == null) throw new AssertionError("no resumed XiaoAi activity or shortcut overlay");
        Object service = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
        String observation = String.valueOf(invoke(invoke(service, "controlApp", "只观察测试页面，不操作"), "getText"));
        var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        if (!session.find()) throw new AssertionError("no UI session: " + observation.substring(0, Math.min(180, observation.length())));
        try {
            assertFocus(context, "正在操作手机");
            if (compact) {
                if (MainThread.call(root::isShown)) throw new AssertionError("shortcut overlay still covers the operated app");
                var flags = MainThread.call(() -> ((android.view.WindowManager.LayoutParams)root.getLayoutParams()).flags);
                if ((flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) throw new AssertionError("shortcut window still intercepts touches");
            } else if (MainThread.call(HookSmokeProbe::resumedActivity) == origin) throw new AssertionError("XiaoAi origin was not backgrounded");
            Thread.sleep(10000); // Host can inspect the actual HyperOS island during this observation-only session.
        } finally {
            invoke(service, "controlApp", "MIAIPLUS:{\"session\":\"" + session.group(1) + "\",\"action\":{\"action\":\"finish\",\"message\":\"界面观察结束\"}}");
        }
        Thread.sleep(2000);
        if (compact) {
            assertFocus(context, "手机操作已结束");
            if (MainThread.call(root::isShown)) throw new AssertionError("shortcut overlay returned over completion island");
            Thread.sleep(6000); // Capture automatically expanded completion island before exercising its return action.
            for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                if (notification.getId() == 0x4d494149) notification.getNotification().contentIntent.send();
            Thread.sleep(1500);
            if (MainThread.call(HookSmokeProbe::resumedActivity) == null) throw new AssertionError("native conversation entry did not open");
        }
        else if (MainThread.call(HookSmokeProbe::resumedActivity) != origin) throw new AssertionError("did not restore the same full-screen Activity instance");
        return "PROBE PASS: focus notification, backgrounded origin, " + (compact ? "shortcut overlay hidden, completion island and native conversation entry opened" : "same fullscreen Activity restored");
    }
    private static void assertFocus(Context context, String title) throws Exception {
        for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() != 0x4d494149) continue;
            var extras = notification.getNotification().extras;
            if (!title.contentEquals(extras.getCharSequence("android.title"))) continue;
            var params = new org.json.JSONObject(extras.getString("miui.focus.param")).getJSONObject("param_v2");
            if (params.getBoolean("islandFirstFloat") && params.getBoolean("enableFloat") && extras.getBundle("miui.focus.pics") != null) return;
        }
        throw new AssertionError("focus notification missing: " + title);
    }
    private static Object currentMode(Object manager) throws Exception {
        for (Method method : manager.getClass().getDeclaredMethods())
            if (method.getName().equals("getCurrentMode") && method.getReturnType().getName().equals("io1.a")) return method.invoke(manager);
        throw new AssertionError("stock mode getter missing");
    }
    private static String transportFailures(ClassLoader loader) throws Exception {
        for (String api : List.of("chat", "responses")) {
            for (String mode : List.of("reset", "cancel", "invalid")) {
                io.github.miaiplus.hook.Hooks.log("MiAIPlus transport fixture: " + api + " " + mode);
                try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                    server.setSoTimeout(5000);
                    CompletableFuture<Boolean> accepted = new CompletableFuture<>();
                    CompletableFuture<Boolean> release = new CompletableFuture<>();
                    if (mode.equals("cancel")) new Thread(() -> {
                        try (var socket = server.accept()) {
                            socket.getInputStream().read(); accepted.complete(true);
                            release.get(8, TimeUnit.SECONDS);
                        } catch (Exception failure) { accepted.completeExceptionally(failure); }
                    }, "miaiplus-local-cancel").start();
                    Object builder = Reflector.newInstance(Reflector.findClass("gg2.d0$a", loader));
                    invoke(builder, "addInterceptor", NativeResponsesAdapter.interceptor(loader, api, "default"));
                    if (mode.equals("reset")) invoke(builder, "addInterceptor", Proxy.newProxyInstance(loader,
                        new Class<?>[]{Reflector.findClass("gg2.w", loader)}, (p, m, args) -> {
                            if (m.getName().equals("intercept")) throw new java.io.IOException("stream was reset: CANCEL");
                            return null;
                        }));
                    invoke(builder, "callTimeout", 5L, TimeUnit.SECONDS);
                    Object client = invoke(builder, "build");
                    Object request = Reflector.newInstance(Reflector.findClass("gg2.f0$a", loader));
                    invoke(request, "url", "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions");
                    Object media = Reflector.callStaticMethod(Reflector.findClass("gg2.z", loader), "parse", "application/json");
                    Object body = Reflector.callStaticMethod(Reflector.findClass("gg2.g0", loader), "create", media,
                        mode.equals("invalid") ? "{" : "{\"model\":\"fixture-model\",\"messages\":[{\"role\":\"user\",\"content\":\"fixture\"}]}");
                    invoke(request, "post", body);
                    Object call = invoke(client, "newCall", invoke(request, "build"));
                    CompletableFuture<Boolean> failed = new CompletableFuture<>();
                    Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("gg2.f", loader)}, (p, m, args) -> {
                        if (m.getName().equals("onFailure")) failed.complete(args[1] instanceof java.io.IOException);
                        else if (m.getName().equals("onResponse")) { invoke(args[1], "close"); failed.complete(false); }
                        return null;
                    });
                    try {
                        invoke(call, "enqueue", callback);
                        if (mode.equals("cancel")) { accepted.get(5, TimeUnit.SECONDS); invoke(call, "cancel"); }
                        if (!failed.get(8, TimeUnit.SECONDS)) throw new AssertionError("failure did not reach IOException callback");
                    } finally { release.complete(true); invoke(call, "cancel"); }
                }
            }
        }
        return "PROBE PASS: real native async OkHttp handles reflected cancellation, disconnect and malformed payload for Chat/Responses without process crash";
    }
    private static Object invoke(Object object, String method, Object... args) { return Reflector.callMethod(object, method, args); }
    private static Object message(ClassLoader loader, String role, String text, String callId) {
        return Reflector.newInstance(Reflector.findClass("com.aios.osbot.agent.model.Message", loader),
            UUID.randomUUID().toString(), enumValue(loader, "MessageRole", role), text, null, callId, null, null,
            System.currentTimeMillis(), enumValue(loader, "MessageSource", "CHAT"), null, null, null);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(ClassLoader loader, String name, String value) {
        return Enum.valueOf((Class)Reflector.findClass("com.aios.osbot.agent.model." + name, loader), value);
    }
    private static String run(Context context, ClassLoader loader, String fixtureToken) throws Exception {
        var settings = NativeModelHook.config(context);
        chatRouting(context, loader);
        if (Set.of("gpt-6.1-sol", "qwen3.8-max").contains(settings.getString("model"))) reasoningUi(context, loader, false);
        Object ongoing = Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
            "CONTINUE：控屏尚未完成。 MIAIPLUS_FRAME:00000000-0000-0000-0000-000000000000", Map.of("cli_internal_direct_answer", true), null, List.of(), true, null, false);
        if (Boolean.TRUE.equals(invoke(ongoing, "isDirectAnswer")) || ((Map<?, ?>)invoke(ongoing, "getMetadata")).containsKey("cli_internal_direct_answer"))
            throw new AssertionError("ongoing observation returned as final answer");
        Object service = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
        Object original = invoke(service, "getCurrentPackage");
        if (!String.valueOf(invoke(original, "getText")).contains("foreground_package")) throw new AssertionError("stock tool failed");
        boolean locked = context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
        String observation;
        if (locked) {
            Object result = invoke(service, "controlApp", "只观察当前测试页面，不操作");
            if (!String.valueOf(invoke(result, "getText")).contains("解锁")) throw new AssertionError("lock gate bypassed");
            observation = fixtureToken;
        } else {
            Object result = invoke(service, "controlApp", "只观察当前测试页面，不操作");
            observation = String.valueOf(invoke(result, "getText"));
            if (!observation.contains("CONTINUE")) throw new AssertionError("controlApp not intercepted: " + observation);
            boolean focusVisible = false;
            for (android.service.notification.StatusBarNotification notification :
                    context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                if (notification.getId() == 0x4d494149 && "com.miui.voiceassist".equals(notification.getPackageName())) {
                    focusVisible = "正在操作手机".contentEquals(notification.getNotification().extras.getCharSequence("android.title"));
                    break;
                }
            }
            if (!focusVisible) throw new AssertionError("focus notification was not visible during controlApp");
            var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
            if (!session.find()) throw new AssertionError("no session");
            invoke(service, "controlApp", "MIAIPLUS:{\"session\":\"" + session.group(1) + "\",\"action\":{\"action\":\"finish\",\"message\":\"观察测试结束\"}}");
        }
        Object toolMessage = message(loader, "TOOL", observation, "native-observation");
        List<?> images = (List<?>)invoke(toolMessage, "getImages");
        if (images == null || images.size() != 1) throw new AssertionError("native image not attached");
        Object ordinary = Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
            "ordinary tool answer", Map.of("cli_internal_direct_answer", true), null, List.of(), true, null, false);
        if (!Boolean.TRUE.equals(invoke(ordinary, "isDirectAnswer"))) throw new AssertionError("unrelated direct answer changed");
        if (!locked) {
            Object resultWithImage = Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
                "CONTINUE：控屏尚未完成。 " + fixtureToken, Map.of("cli_internal_direct_answer", true), null, List.of(), true, null, false);
            List<?> resultImages = (List<?>)invoke(resultWithImage, "getImages");
            if (Boolean.TRUE.equals(invoke(resultWithImage, "isDirectAnswer")) || resultImages == null || resultImages.size() != 1)
                throw new AssertionError("native ToolResult image/continuation failed");
            if (((Number)invoke(resultImages.get(0), "getWidth")).intValue() != 64) throw new AssertionError("encoded image dimensions lost");
        }
        Object client = dynClient;
        if (client == null) throw new AssertionError("DynLlmClient not initialized");
        Object config = Reflector.newInstance(Reflector.findClass("sm.a", loader), "unused-native", 0.0, Integer.valueOf(1024), 1.0,
            null, null, "MIAIPLUS_NATIVE_SYSTEM", null, false, "taiyi", null, false,
            Reflector.newInstance(Reflector.findClass("sm.f", loader)), null, false);
        Object json = Reflector.getStaticObjectField(Reflector.findClass("s92.b", loader), "d");
        Object schema = invoke(json, "parseToJsonElement", "{\"type\":\"object\",\"properties\":{}}");
        Object tool = Reflector.newInstance(Reflector.findClass("com.aios.osbot.llm.model.ToolDefinition", loader), "probe_tool", "Native tool schema preservation test", schema);
        List<Object> clients = new ArrayList<>(); clients.add(client);
        Object http = null;
        for (var field : client.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals("io.ktor.client.HttpClient")) { field.setAccessible(true); http = field.get(client); break; }
        }
        if (http == null) throw new AssertionError("native HTTP fixture unavailable");
        for (String type : new String[]{"qm.i", "qm.j", "qm.b"})
            clients.add(Reflector.newInstance(Reflector.findClass(type, loader), "unused-native-key", "http://127.0.0.1:1", http));
        for (Object transport : clients) {
            List<Object> events = collect(loader, transport, List.of(message(loader, "USER", "MIAIPLUS_NATIVE_STREAM_TEST", null)), List.of(tool), config);
            if (events.stream().noneMatch(e -> e.getClass().getName().equals("sm.b$f"))) throw new AssertionError("no native tool-call event for " + transport.getClass().getName() + ": " + events);
            Object called = events.stream().filter(e -> e.getClass().getName().equals("sm.b$f")).findFirst().orElseThrow();
            Object nativeCall = Reflector.newInstance(Reflector.findClass("com.aios.osbot.agent.model.ToolCall", loader),
                invoke(called, "getId"), invoke(called, "getName"), invoke(called, "getArguments"), "probe-result",
                enumValue(loader, "ToolStatus", "COMPLETED"), List.of(), null, null, null);
            Object assistant = Reflector.newInstance(Reflector.findClass("com.aios.osbot.agent.model.Message", loader),
                UUID.randomUUID().toString(), enumValue(loader, "MessageRole", "ASSISTANT"), "", List.of(nativeCall), null, null, null,
                System.currentTimeMillis(), enumValue(loader, "MessageSource", "CHAT"), null, null, null);
            Object toolResult = Reflector.newInstance(Reflector.findClass("com.aios.osbot.agent.model.Message", loader),
                UUID.randomUUID().toString(), enumValue(loader, "MessageRole", "TOOL"), "probe-result", null, invoke(called, "getId"), null, images,
                System.currentTimeMillis(), enumValue(loader, "MessageSource", "CHAT"), null, null, null);
            List<Object> continued = collect(loader, transport,
                List.of(message(loader, "USER", "MIAIPLUS_NATIVE_STREAM_TEST", null), assistant, toolResult), List.of(tool), config);
            if (continued.stream().noneMatch(e -> e.getClass().getName().equals("sm.b$d") && String.valueOf(invoke(e, "getText")).contains("native-roundtrip-ok")))
                throw new AssertionError("native tool result/image roundtrip failed for " + transport.getClass().getName() + ": " + continued);
            List<Object> textEvents = collect(loader, transport, List.of(message(loader, "USER", "MIAIPLUS_NATIVE_STREAM_FINAL", null)), List.of(), config);
            if (textEvents.stream().noneMatch(e -> e.getClass().getName().equals("sm.b$d") && String.valueOf(invoke(e, "getText")).contains("native-stream-ok")))
                throw new AssertionError("no native text event for " + transport.getClass().getName() + ": " + textEvents);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object background = Enum.valueOf((Class)Reflector.findClass("gp.e", loader), "BACKGROUND");
        Object memory = Reflector.newInstance(Reflector.findClass("ep.j", loader), context, 0.0, 1024, Long.valueOf(15000), false, null, "miaiplus-test", background, false);
        CompletableFuture<Object> memoryDone = new CompletableFuture<>();
        Object raw = await(loader, invoke(memory, "o", config, "MIAIPLUS_MEMORY_SYSTEM", "MIAIPLUS_MEMORY_TEST", "miaiplus-probe", 15000L, null, continuation(loader, memoryDone)), memoryDone);
        if (!String.valueOf(raw).contains("memory-ok")) throw new AssertionError("memory model override failed");
        Object gate = Reflector.newInstance(Reflector.findClass("go.c", loader), context,
            "http://127.0.0.1:1", "unused-gate-model", 15000L, 0.0, 300);
        CompletableFuture<Object> gateDone = new CompletableFuture<>();
        Object gateResult = await(loader, invoke(gate, "chat", "MIAIPLUS_GATE_SYSTEM", "MIAIPLUS_GATE_TEST", "miaiplus-test", continuation(loader, gateDone)), gateDone);
        if (!"gate-ok".equals(invoke(gateResult, "getText"))) throw new AssertionError("gate override failed");
        Object routeConfig = Reflector.newInstance(Reflector.findClass("com.aios.osbot.router.d0", loader));
        Object routeLlm = Reflector.newInstance(Reflector.findClass("com.aios.osbot.router.n1", loader),
            "http://127.0.0.1:1/osbot/api/intent/v2/chat/completions?bizId=private", "unused-key", "unused-router-model", "unused-provider", 15000, null);
        invoke(routeConfig, "setLlmConfig", routeLlm);
        invoke(routeConfig, "setCustomPrompt", "MIAIPLUS_CAPABILITY_SYSTEM");
        Object capability = Reflector.newInstance(Reflector.findClass("com.aios.osbot.router.a", loader), http);
        CompletableFuture<Object> capabilityDone = new CompletableFuture<>();
        Object capabilityResult = await(loader, invoke(capability, "b", routeConfig, "MIAIPLUS_CAPABILITY_TEST", continuation(loader, capabilityDone)), capabilityDone);
        if (!"capability-ok".equals(invoke(capabilityResult, "getContent"))) throw new AssertionError("capability override failed");
        Class<?> loggerType = Reflector.findClass("r32.e", loader);
        Object logger = Proxy.newProxyInstance(loader, new Class<?>[]{loggerType}, (p,m,args) -> null);
        Object router = Reflector.newInstance(Reflector.findClass("com.aios.osbot.router.t", loader), http, logger);
        CompletableFuture<Object> routerDone = new CompletableFuture<>();
        Object routerResult = await(loader, invoke(router, "b", routeConfig, "MIAIPLUS_ROUTER_TEST", null, continuation(loader, routerDone)), routerDone);
        Object decision = invoke(routerResult, "getResult");
        if (decision == null || !"FALLBACK".equals(invoke(decision, "getStatus"))) throw new AssertionError("router protocol adaptation failed");
        return "PROBE PASS: actual controlApp hook (lock gate or native observation); stock getCurrentPackage; native images and continuation; Dyn/direct model and memory overrides; SSE text/tools; actual gate, capability and intent router requests plus native response parsing";
    }
    private static void chatRouting(Context context, ClassLoader loader) throws Exception {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Object callback = chatCallback.get();
                if (callback == null) throw new AssertionError("native text-chat callback unavailable");
                Method route = Reflector.findClass("ll1.f0", loader).getDeclaredMethod("C");
                boolean actual = (Boolean)route.invoke(callback);
                boolean original = (Boolean)io.github.miaiplus.hook.Hooks.invokeOriginalMethod(route, callback, new Object[0]);
                boolean external = NativeModelHook.config(context).getBoolean("globalModel");
                if (actual != (external || original)) throw new AssertionError("text chat bypassed external model or changed stock routing");
                done.complete(true);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(10, TimeUnit.SECONDS);
    }
    private static String reasoningUi(Context context, ClassLoader loader, boolean stock) throws Exception {
        chatRouting(context, loader);
        CompletableFuture<String> done = new CompletableFuture<>();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                android.view.View input = chatInput.get();
                if (input == null || !input.isAttachedToWindow()) throw new AssertionError("chat mode entry not attached");
                var anchor = (android.widget.TextView)Reflector.getObjectField(Reflector.getObjectField(input, "I"), "c");
                Object manager = Reflector.getStaticObjectField(Reflector.findClass("io1.h", loader), "a");
                Object before = currentMode(manager);
                invoke(input, "updateAiModeChip", before);
                var settings = NativeModelHook.config(context);
                if (settings.getBoolean("globalModel") == stock) throw new AssertionError("wrong reasoning fixture config");
                Object popupFactory = Reflector.getStaticObjectField(Reflector.findClass("uo1.f", loader), "d");
                int[] callbacks = {0};
                invoke(popupFactory, "showPopup", input.getContext(), anchor, input, before,
                    NativeModelHook.function(loader, ignored -> callbacks[0]++), false, false, null);
                if (stock) {
                    if (NativeReasoningHook.activePopup() != null && NativeReasoningHook.activePopup().isShowing())
                        throw new AssertionError("external popup replaced stock mode");
                    Object visible = Reflector.getStaticObjectField(Reflector.findClass("uo1.f", loader), "g");
                    if (visible == null) throw new AssertionError("stock popup not shown");
                    Object[] values = (Object[])Reflector.callStaticMethod(Reflector.findClass("io1.a", loader), "values");
                    if (!Arrays.stream(values).map(String::valueOf).collect(java.util.stream.Collectors.toList()).equals(List.of("AUTO", "QUICK", "EXPERT")))
                        throw new AssertionError("stock choices changed");
                    if (!anchor.getText().toString().equals(input.getContext().getString(((Number)invoke(before, "getTitleResId")).intValue())))
                        throw new AssertionError("stock label changed");
                    invoke(popupFactory, "dismissPopup");
                } else {
                    android.widget.PopupWindow popup = NativeReasoningHook.activePopup();
                    if (popup == null || !popup.isShowing()) throw new AssertionError("external popup missing");
                    android.widget.ListView list = (android.widget.ListView)popup.getContentView();
                    String expected = "responses".equals(settings.getString("api")) ? "high" : "on";
                    var choices = ReasoningPolicy.choices(settings.getString("model"), settings.getString("api"));
                    if (list.getAdapter().getCount() != choices.size()) throw new AssertionError("wrong effort choices");
                    for (int i = 0; i < choices.size(); i++)
                        if (!ReasoningPolicy.label(choices.get(i)).equals(list.getAdapter().getItem(i))) throw new AssertionError("wrong effort label");
                    int position = choices.indexOf(expected);
                    list.performItemClick(null, position, position);
                    if (!expected.equals(NativeModelHook.config(context).getString("reasoning"))) throw new AssertionError("effort not persisted through bridge");
                    if (callbacks[0] != 0 || currentMode(manager) != before) throw new AssertionError("stock mode overwritten");
                    // Reopen and verify persisted selection, then close without changing it.
                    invoke(popupFactory, "showPopup", input.getContext(), anchor, input, before,
                        NativeModelHook.function(loader, ignored -> callbacks[0]++), false, false, null);
                    popup = NativeReasoningHook.activePopup();
                    if (((android.widget.ListView)popup.getContentView()).getCheckedItemPosition() != position)
                        throw new AssertionError("selection not restored");
                    popup.dismiss();
                }
                android.view.View bar = functionBar.get();
                if (bar == null || !bar.isAttachedToWindow() || !bar.isShown()) throw new AssertionError("full-screen chat entry not visible");
                Object viewModel = invoke(bar, "getViewModel");
                Object fullMode = invoke(viewModel, "x");
                List<?> fullItems = (List<?>)invoke(viewModel, "w");
                if (fullItems.size() != 3) throw new AssertionError("stock full-screen choices changed");
                Object effect = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.b$b", loader),
                    0, fullMode, fullItems);
                invoke(bar, "v", effect);
                Object factory = Reflector.getStaticObjectField(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.d", loader), "d");
                if (stock) {
                    Object nativePopup = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.d", loader), "access$getCurrentVisible$cp");
                    if (nativePopup == null || !Boolean.TRUE.equals(invoke(Reflector.getObjectField(nativePopup, "a"), "isShowing")))
                        throw new AssertionError("stock full-screen popup missing");
                    invoke(factory, "dismiss");
                } else {
                    android.widget.PopupWindow popup = NativeReasoningHook.activePopup();
                    if (popup == null || !popup.isShowing()) throw new AssertionError("full-screen effort menu missing");
                    android.widget.ListView list = (android.widget.ListView)popup.getContentView();
                    var choices = ReasoningPolicy.choices(settings.getString("model"), settings.getString("api"));
                    String expected = "responses".equals(settings.getString("api")) ? "high" : "on";
                    if (list.getAdapter().getCount() != choices.size()) throw new AssertionError("wrong full-screen efforts");
                    list.performItemClick(null, choices.indexOf(expected), choices.indexOf(expected));
                    if (currentMode(manager) != before) throw new AssertionError("full-screen selection overwrote Xiaomi mode");
                    if (!ReasoningPolicy.label(expected).equals(invoke(viewModel, "Z", fullMode))) throw new AssertionError("full-screen effort label missing");
                }
                done.complete("PROBE PASS: native voice-bar and full-screen chat reasoning entries " + (stock ? "retain stock AUTO/QUICK/EXPERT and labels" : "select and persist model effort without changing stock mode"));
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        return done.get(15, TimeUnit.SECONDS);
    }
    private static Object continuation(ClassLoader loader, CompletableFuture<Object> future) {
        Class<?> type = Reflector.findClass("kotlin.coroutines.Continuation", loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("getContext")) return Reflector.getStaticObjectField(Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
            if (method.getName().equals("resumeWith")) { future.complete(args[0]); return null; }
            return "probe-continuation";
        });
    }
    private static Object await(ClassLoader loader, Object value, CompletableFuture<Object> completion) throws Exception {
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(40, TimeUnit.SECONDS) : value;
        Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", result);
        return result;
    }
    private static List<Object> collect(ClassLoader loader, Object client, List<?> messages, List<?> tools, Object config) throws Exception {
        CompletableFuture<Object> started = new CompletableFuture<>();
        Object flow = await(loader, invoke(client, "streamCompletion", messages, tools, config, continuation(loader, started)), started);
        List<Object> events = new ArrayList<>();
        Class<?> collectorType = Reflector.findClass("z82.j", loader);
        Object collector = Proxy.newProxyInstance(loader, new Class<?>[]{collectorType}, (p,m,args) -> {
            if (m.getName().equals("emit")) { events.add(args[0]); return Reflector.getStaticObjectField(Reflector.findClass("kotlin.Unit", loader), "INSTANCE"); }
            return "probe-collector";
        });
        CompletableFuture<Object> completed = new CompletableFuture<>();
        await(loader, invoke(flow, "collect", collector, continuation(loader, completed)), completed);
        return events;
    }
}
