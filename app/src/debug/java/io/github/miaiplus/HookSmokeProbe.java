package io.github.miaiplus;

import android.content.*;
import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Debug APK only. Exercises native constructors, real hooks and native SSE client in the scoped process. */
public final class HookSmokeProbe {
    private static volatile Object dynClient;
    public static void install(Context context, ClassLoader loader) {
        de.robv.android.xposed.XposedBridge.hookAllConstructors(XposedHelpers.findClass("qm.c", loader), new de.robv.android.xposed.XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { dynClient = p.thisObject; }
        });
        for (String name : new String[]{"qm.c", "um.h", "com.xiaomi.voiceassistant.UIAgentServiceForOSBot", "as.a"}) {
            Class<?> type = XposedHelpers.findClass(name, loader);
            for (var method : type.getDeclaredMethods()) {
                if (!Set.of("streamCompletion", "controlApp", "build", "buildSessionHead", "buildTurnInject").contains(method.getName())) continue;
                de.robv.android.xposed.XposedBridge.hookMethod(method, new de.robv.android.xposed.XC_MethodHook(10000) {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        de.robv.android.xposed.XposedBridge.log("MiAIPlus LIVE: " + name + "." + method.getName()
                            + (method.getName().equals("controlApp") ? " step=" + (p.args[0] instanceof String q && q.startsWith("MIAIPLUS:")) : ""));
                    }
                });
            }
        }
        for (String name : new String[]{"com.aios.osbot.tools.ToolResult$Success", "com.aios.osbot.agent.model.Message"}) {
            de.robv.android.xposed.XposedBridge.hookAllConstructors(XposedHelpers.findClass(name, loader), new de.robv.android.xposed.XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (name.endsWith("Success")) {
                        String text = String.valueOf(invoke(p.thisObject, "getResult"));
                        if (text.contains("CONTINUE：控屏尚未完成。")) de.robv.android.xposed.XposedBridge.log("MiAIPlus LIVE: observation direct=" + invoke(p.thisObject, "isDirectAnswer"));
                    } else {
                        String text = String.valueOf(invoke(p.thisObject, "getContent"));
                        if (text.contains("CONTINUE：控屏尚未完成。")) de.robv.android.xposed.XposedBridge.log("MiAIPlus LIVE: observation Message role=" + invoke(p.thisObject, "getRole") + " images=" + (invoke(p.thisObject, "getImages") != null));
                    }
                }
            });
        }
        if (!android.app.Application.getProcessName().equals("com.miui.voiceassist")) return;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                var pending = goAsync();
                new Thread(() -> {
                    String report;
                    try { report = run(context, loader, intent.getStringExtra("frame")); }
                    catch (Throwable e) { report = "PROBE FAIL: " + e; de.robv.android.xposed.XposedBridge.log(e); }
                    try { Bundle b = new Bundle(); b.putString("report", report); context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, b); }
                    finally { pending.finish(); }
                }, "miaiplus-debug-probe").start();
            }
        }, new IntentFilter("io.github.miaiplus.DEBUG_PROBE"), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
    }
    private static Object invoke(Object object, String method, Object... args) { return XposedHelpers.callMethod(object, method, args); }
    private static Object message(ClassLoader loader, String role, String text, String callId) {
        return XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.agent.model.Message", loader),
            UUID.randomUUID().toString(), enumValue(loader, "MessageRole", role), text, null, callId, null, null,
            System.currentTimeMillis(), enumValue(loader, "MessageSource", "CHAT"), null, null, null);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(ClassLoader loader, String name, String value) {
        return Enum.valueOf((Class)XposedHelpers.findClass("com.aios.osbot.agent.model." + name, loader), value);
    }
    private static String run(Context context, ClassLoader loader, String fixtureToken) throws Exception {
        Object ongoing = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
            "CONTINUE：控屏尚未完成。 MIAIPLUS_FRAME:00000000-0000-0000-0000-000000000000", Map.of("cli_internal_direct_answer", true), null, List.of(), true, null, false);
        if (Boolean.TRUE.equals(invoke(ongoing, "isDirectAnswer")) || ((Map<?, ?>)invoke(ongoing, "getMetadata")).containsKey("cli_internal_direct_answer"))
            throw new AssertionError("ongoing observation returned as final answer");
        Object service = XposedHelpers.newInstance(XposedHelpers.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
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
            var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
            if (!session.find()) throw new AssertionError("no session");
            invoke(service, "controlApp", "MIAIPLUS:{\"session\":\"" + session.group(1) + "\",\"action\":{\"action\":\"finish\",\"message\":\"观察测试结束\"}}");
        }
        Object toolMessage = message(loader, "TOOL", observation, "native-observation");
        List<?> images = (List<?>)invoke(toolMessage, "getImages");
        if (images == null || images.size() != 1) throw new AssertionError("native image not attached");
        Object ordinary = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
            "ordinary tool answer", Map.of("cli_internal_direct_answer", true), null, List.of(), true, null, false);
        if (!Boolean.TRUE.equals(invoke(ordinary, "isDirectAnswer"))) throw new AssertionError("unrelated direct answer changed");
        if (!locked) {
            Object resultWithImage = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
                "CONTINUE：控屏尚未完成。 " + fixtureToken, Map.of("cli_internal_direct_answer", true), null, List.of(), true, null, false);
            List<?> resultImages = (List<?>)invoke(resultWithImage, "getImages");
            if (Boolean.TRUE.equals(invoke(resultWithImage, "isDirectAnswer")) || resultImages == null || resultImages.size() != 1)
                throw new AssertionError("native ToolResult image/continuation failed");
            if (((Number)invoke(resultImages.get(0), "getWidth")).intValue() != 64) throw new AssertionError("encoded image dimensions lost");
        }
        Object client = dynClient;
        if (client == null) throw new AssertionError("DynLlmClient not initialized");
        Object config = XposedHelpers.newInstance(XposedHelpers.findClass("sm.a", loader), "unused-native", 0.0, Integer.valueOf(1024), 1.0,
            null, null, "MIAIPLUS_NATIVE_SYSTEM", null, false, "taiyi", null, false,
            XposedHelpers.newInstance(XposedHelpers.findClass("sm.f", loader)), null, false);
        Object json = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("s92.b", loader), "d");
        Object schema = invoke(json, "parseToJsonElement", "{\"type\":\"object\",\"properties\":{}}");
        Object tool = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.llm.model.ToolDefinition", loader), "probe_tool", "Native tool schema preservation test", schema);
        List<Object> clients = new ArrayList<>(); clients.add(client);
        Object http = null;
        for (var field : client.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals("io.ktor.client.HttpClient")) { field.setAccessible(true); http = field.get(client); break; }
        }
        if (http == null) throw new AssertionError("native HTTP fixture unavailable");
        for (String type : new String[]{"qm.i", "qm.j", "qm.b"})
            clients.add(XposedHelpers.newInstance(XposedHelpers.findClass(type, loader), "unused-native-key", "http://127.0.0.1:1", http));
        for (Object transport : clients) {
            List<Object> events = collect(loader, transport, List.of(message(loader, "USER", "MIAIPLUS_NATIVE_STREAM_TEST", null)), List.of(tool), config);
            if (events.stream().noneMatch(e -> e.getClass().getName().equals("sm.b$f"))) throw new AssertionError("no native tool-call event for " + transport.getClass().getName() + ": " + events);
            List<Object> textEvents = collect(loader, transport, List.of(message(loader, "USER", "MIAIPLUS_NATIVE_STREAM_FINAL", null)), List.of(), config);
            if (textEvents.stream().noneMatch(e -> e.getClass().getName().equals("sm.b$d") && String.valueOf(invoke(e, "getText")).contains("native-stream-ok")))
                throw new AssertionError("no native text event for " + transport.getClass().getName() + ": " + textEvents);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object background = Enum.valueOf((Class)XposedHelpers.findClass("gp.e", loader), "BACKGROUND");
        Object memory = XposedHelpers.newInstance(XposedHelpers.findClass("ep.j", loader), context, 0.0, 1024, Long.valueOf(15000), false, null, "miaiplus-test", background, false);
        CompletableFuture<Object> memoryDone = new CompletableFuture<>();
        Object raw = await(loader, invoke(memory, "o", config, "MIAIPLUS_MEMORY_SYSTEM", "MIAIPLUS_MEMORY_TEST", "miaiplus-probe", 15000L, null, continuation(loader, memoryDone)), memoryDone);
        if (!String.valueOf(raw).contains("memory-ok")) throw new AssertionError("memory model override failed");
        Object gate = XposedHelpers.newInstance(XposedHelpers.findClass("go.c", loader), context,
            "http://127.0.0.1:1", "unused-gate-model", 15000L, 0.0, 300);
        CompletableFuture<Object> gateDone = new CompletableFuture<>();
        Object gateResult = await(loader, invoke(gate, "chat", "MIAIPLUS_GATE_SYSTEM", "MIAIPLUS_GATE_TEST", "miaiplus-test", continuation(loader, gateDone)), gateDone);
        if (!"gate-ok".equals(invoke(gateResult, "getText"))) throw new AssertionError("gate override failed");
        Object routeConfig = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.router.d0", loader));
        Object routeLlm = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.router.n1", loader),
            "http://127.0.0.1:1/osbot/api/intent/v2/chat/completions?bizId=private", "unused-key", "unused-router-model", "unused-provider", 15000, null);
        invoke(routeConfig, "setLlmConfig", routeLlm);
        invoke(routeConfig, "setCustomPrompt", "MIAIPLUS_CAPABILITY_SYSTEM");
        Object capability = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.router.a", loader), http);
        CompletableFuture<Object> capabilityDone = new CompletableFuture<>();
        Object capabilityResult = await(loader, invoke(capability, "b", routeConfig, "MIAIPLUS_CAPABILITY_TEST", continuation(loader, capabilityDone)), capabilityDone);
        if (!"capability-ok".equals(invoke(capabilityResult, "getContent"))) throw new AssertionError("capability override failed");
        Class<?> loggerType = XposedHelpers.findClass("r32.e", loader);
        Object logger = Proxy.newProxyInstance(loader, new Class<?>[]{loggerType}, (p,m,args) -> null);
        Object router = XposedHelpers.newInstance(XposedHelpers.findClass("com.aios.osbot.router.t", loader), http, logger);
        CompletableFuture<Object> routerDone = new CompletableFuture<>();
        Object routerResult = await(loader, invoke(router, "b", routeConfig, "MIAIPLUS_ROUTER_TEST", null, continuation(loader, routerDone)), routerDone);
        Object decision = invoke(routerResult, "getResult");
        if (decision == null || !"FALLBACK".equals(invoke(decision, "getStatus"))) throw new AssertionError("router protocol adaptation failed");
        return "PROBE PASS: actual controlApp hook (lock gate or native observation); stock getCurrentPackage; native images and continuation; Dyn/direct model and memory overrides; SSE text/tools; actual gate, capability and intent router requests plus native response parsing";
    }
    private static Object continuation(ClassLoader loader, CompletableFuture<Object> future) {
        Class<?> type = XposedHelpers.findClass("kotlin.coroutines.Continuation", loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("getContext")) return XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
            if (method.getName().equals("resumeWith")) { future.complete(args[0]); return null; }
            return "probe-continuation";
        });
    }
    private static Object await(ClassLoader loader, Object value, CompletableFuture<Object> completion) throws Exception {
        Object suspended = XposedHelpers.callStaticMethod(XposedHelpers.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(40, TimeUnit.SECONDS) : value;
        XposedHelpers.callStaticMethod(XposedHelpers.findClass("kotlin.ResultKt", loader), "throwOnFailure", result);
        return result;
    }
    private static List<Object> collect(ClassLoader loader, Object client, List<?> messages, List<?> tools, Object config) throws Exception {
        CompletableFuture<Object> started = new CompletableFuture<>();
        Object flow = await(loader, invoke(client, "streamCompletion", messages, tools, config, continuation(loader, started)), started);
        List<Object> events = new ArrayList<>();
        Class<?> collectorType = XposedHelpers.findClass("z82.j", loader);
        Object collector = Proxy.newProxyInstance(loader, new Class<?>[]{collectorType}, (p,m,args) -> {
            if (m.getName().equals("emit")) { events.add(args[0]); return XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.Unit", loader), "INSTANCE"); }
            return "probe-collector";
        });
        CompletableFuture<Object> completed = new CompletableFuture<>();
        await(loader, invoke(flow, "collect", collector, continuation(loader, completed)), completed);
        return events;
    }
}
