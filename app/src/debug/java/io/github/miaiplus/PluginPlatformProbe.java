package io.github.miaiplus;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only, DUMP-protected acceptance of the actual host registry and native plugin proxies. */
public final class PluginPlatformProbe {
    private static final Set<Thread> WORKERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, TermuxHostRun> TERMUX_RUNS = new ConcurrentHashMap<>();
    private static BroadcastReceiver receiver;
    private static Context receiverContext;

    public static synchronized void install(Context context, ClassLoader loader) {
        if (!"com.miui.voiceassist".equals(Application.getProcessName()) || receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String supplied = intent.getStringExtra("nonce");
                String nonce = supplied == null ? UUID.randomUUID().toString() : supplied;
                if (!nonce.matches("[A-Za-z0-9._-]{1,100}")) return;
                String tool = intent.getStringExtra("tool");
                String phone = intent.getStringExtra("phone");
                boolean termux = "termux".equals(intent.getStringExtra("mode"));
                String expectedUid = intent.getStringExtra("expected_uid");
                if (termux || "termux_cancel".equals(intent.getStringExtra("mode"))) {
                    if (!nonce.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) return;
                    termuxRequest(context, loader, tool, nonce, expectedUid, !termux);
                    return;
                }
                try { Hooks.enter(); }
                catch (IllegalStateException retiring) { return; }
                Thread worker = new Thread(() -> {
                    String report;
                    try {
                        report = "PLUGIN_PLATFORM_PASS " + nonce + " " + run(context, loader, tool, phone, nonce);
                    }
                    catch (Throwable failure) {
                        Hooks.log(failure);
                        report = "PLUGIN_PLATFORM_FAIL " + nonce + " " + failure.getClass().getSimpleName()
                            + ": " + compact(failure.getMessage());
                    }
                    try { report(context, report); }
                    finally { WORKERS.remove(Thread.currentThread()); Hooks.leave(); }
                }, "miaiplus-plugin-platform-probe");
                WORKERS.add(worker);
                try { worker.start(); }
                catch (Throwable failure) { WORKERS.remove(worker); Hooks.leave(); throw failure; }
            }
        };
        // Application.attach may replace its outer Context after installation.
        receiverContext = context.createConfigurationContext(context.getResources().getConfiguration());
        try {
            receiverContext.registerReceiver(receiver, new IntentFilter(BuildConfig.APPLICATION_ID + ".PLUGIN_PROBE"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        } catch (RuntimeException failure) { receiver = null; receiverContext = null; throw failure; }
    }

    public static synchronized void dispose(Context ignored) {
        if (!WORKERS.isEmpty()) throw new IllegalStateException("Plugin platform probe is running");
        try { if (receiver != null) receiverContext.unregisterReceiver(receiver); }
        finally { receiver = null; receiverContext = null; TERMUX_RUNS.clear(); }
    }

    private static synchronized void termuxRequest(Context context, ClassLoader loader, String tool,
        String nonce, String expectedUid, boolean cancel) {
        TermuxHostRun previous = TERMUX_RUNS.get(nonce);
        if (previous != null) { if (cancel) previous.cancel(); return; }
        // Retain completed/cancelled identities until this host generation is disposed. A delayed
        // start broadcast must not reopen a nonce whose cleanup has already been acknowledged.
        if (TERMUX_RUNS.size() >= 16) return;
        try { Hooks.enter(); }
        catch (IllegalStateException retiring) { return; }
        TermuxHostRun run = new TermuxHostRun(nonce,
            () -> "TERMUX_MCP_HOST_PASS " + nonce + " " + termux(context, loader, tool, nonce, expectedUid),
            text -> report(context, text), () -> {
                WORKERS.remove(Thread.currentThread());
                Hooks.leave();
            });
        TERMUX_RUNS.put(nonce, run);
        if (cancel) run.cancel();
        WORKERS.add(run.completion);
        try { run.start(); }
        catch (Throwable failure) { WORKERS.remove(run.completion); Hooks.leave(); throw failure; }
    }

    /** Completion is published only after the actual worker has exited, including cancellation. */
    static final class TermuxHostRun {
        final Thread completion;
        private final Thread worker;
        private final String nonce;
        private volatile String result;
        private boolean cancelled;

        TermuxHostRun(String nonce, Callable<String> action, Consumer<String> report, Runnable released) {
            this.nonce = nonce;
            worker = new Thread(() -> {
                try { result = action.call(); }
                catch (Throwable failure) {
                    result = "TERMUX_MCP_HOST_FAIL " + nonce + " " + failure.getClass().getSimpleName()
                        + ": " + compact(failure.getMessage());
                }
            }, "miaiplus-termux-probe-action");
            completion = new Thread(() -> {
                boolean interrupted = false;
                try {
                    synchronized (this) { if (!cancelled) worker.start(); }
                    while (worker.isAlive()) {
                        try { worker.join(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    String terminal;
                    synchronized (this) {
                        terminal = cancelled ? "TERMUX_MCP_HOST_CANCELLED " + nonce : result;
                    }
                    report.accept(terminal + "\nTERMUX_MCP_HOST_EXITED " + nonce);
                } finally {
                    released.run();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }, "miaiplus-termux-probe-completion");
        }

        void start() { completion.start(); }
        synchronized void cancel() { cancelled = true; worker.interrupt(); }
    }

    private static String run(Context context, ClassLoader loader, String remoteName, String phone, String nonce)
        throws Exception {
        if ("none".equals(phone)) phone = null;
        if (remoteName == null || remoteName.isBlank()) throw new AssertionError("missing MCP echo tool name");
        if (phone != null && !phone.isBlank() && !List.of("foreground", "background").contains(phone))
            throw new AssertionError("unsupported Phone Use probe mode; no background fallback is permitted");
        Bundle before = screenState(context);
        if (before.getBoolean("active")) throw new AssertionError("an existing screen task is active");
        Bundle jobs = context.getContentResolver().call(BridgeProvider.URI, "plugin_busy", null, null);
        if (jobs == null || jobs.getBoolean("active", true)) throw new AssertionError("another plugin job is active");
        Bundle catalog = context.getContentResolver().call(BridgeProvider.URI, "plugin_catalog", null, null);
        if (catalog == null || !NativePluginTools.refresh(context)) throw new AssertionError("plugin catalog unavailable");
        JSONArray specs = new JSONArray(catalog.getString("tools", "[]"));
        Map<String, PluginToolSpec> expected = new java.util.LinkedHashMap<>();
        for (int index = 0; index < specs.length(); index++) {
            PluginToolSpec spec = PluginToolSpec.fromJson(specs.getJSONObject(index));
            if (List.of(PhoneUseRouting.FOREGROUND_TOOL, PhoneUseRouting.BACKGROUND_TOOL, remoteName).contains(spec.name()))
                expected.put(spec.name(), spec);
        }
        if (expected.size() != 3 || !"mcp".equals(expected.get(remoteName).kind()))
            throw new AssertionError("both Phone Use plugins and the requested MCP tool must be enabled");
        for (String name : List.of(PhoneUseRouting.FOREGROUND_TOOL, PhoneUseRouting.BACKGROUND_TOOL))
            if (!PhoneUsePlugin.owns(expected.get(name))) throw new AssertionError("unexpected built-in specification: " + name);

        Object registry = NativePluginTools.registry(context, loader);
        if (registry == null) throw new AssertionError("real host ToolRegistry is unavailable");
        CompletableFuture<Object> definitionsDone = new CompletableFuture<>();
        Object raw = invoke(registry, HostCompatibility.choose("getToolDefinitions", "getAll"),
            continuation(loader, definitionsDone));
        Object exported = await(loader, raw, definitionsDone);
        if (!(exported instanceof List<?> definitions)) throw new AssertionError("host definitions are not a list");
        Object registered = Reflector.getObjectField(registry, "tools");
        if (!(registered instanceof Map<?, ?> tools)) throw new AssertionError("host tool map is unavailable");
        for (PluginToolSpec spec : expected.values()) verifyTool(tools.get(spec.name()), definitions, spec);
        if (tools.get(PhoneUseRouting.FOREGROUND_TOOL) == tools.get(PhoneUseRouting.BACKGROUND_TOOL))
            throw new AssertionError("Phone Use plugins share an executable proxy");

        Object remote = tools.get(remoteName);
        Object result = execute(loader, remote, new JSONObject().put("text", nonce));
        if (isFailure(loader, result)) throw new AssertionError("MCP native proxy failed: " + resultText(loader, result));
        String echoed = resultText(loader, result);
        if (!("MCP 工具返回（远程内容）：\n" + nonce).equals(echoed))
            throw new AssertionError("MCP echo did not return the exact remote-content envelope and nonce");
        if (Boolean.TRUE.equals(invoke(result, "isDirectAnswer"))) throw new AssertionError("MCP tool became a direct answer");
        Object images = invoke(result, "getImages");
        if (images instanceof List<?> list && !list.isEmpty()) throw new AssertionError("MCP tool acquired Phone Use images");
        Bundle after = screenState(context);
        if (after.getBoolean("active") || !java.util.Objects.equals(before.getString("screen_task"), after.getString("screen_task")))
            throw new AssertionError("MCP execution created or changed a screen task");

        String evidence = "real host registry; three unique definitions and matching schemas; current module proxy ownership; "
            + "native MCP echo=" + nonce + "; no screen task";
        if (phone != null && !phone.isBlank()) evidence += "; " + phone(context, loader, tools, phone);
        return evidence;
    }

    private static String termux(Context context, ClassLoader loader, String remoteName, String nonce,
        String expectedUid) throws Exception {
        if ("io.github.miaiplus".equals(BuildConfig.APPLICATION_ID))
            throw new AssertionError("Termux probe requires the independent package");
        if (!nonce.matches("[a-f0-9-]{36}") || expectedUid == null || !expectedUid.matches("[1-9][0-9]{4}"))
            throw new AssertionError("invalid Termux probe identity");
        if (remoteName == null || remoteName.isBlank()) throw new AssertionError("missing Termux MCP tool name");
        Bundle before = screenState(context);
        if (before.getBoolean("active")) throw new AssertionError("an existing screen task is active");
        Bundle jobs = context.getContentResolver().call(BridgeProvider.URI, "plugin_busy", null, null);
        if (jobs == null || jobs.getBoolean("active", true)) throw new AssertionError("another plugin job is active");
        Bundle catalog = context.getContentResolver().call(BridgeProvider.URI, "plugin_catalog", null, null);
        if (catalog == null || !NativePluginTools.refresh(context)) throw new AssertionError("plugin catalog unavailable");
        JSONArray specs = new JSONArray(catalog.getString("tools", "[]"));
        PluginToolSpec expected = null;
        for (int index = 0; index < specs.length(); index++) {
            PluginToolSpec candidate = PluginToolSpec.fromJson(specs.getJSONObject(index));
            if (remoteName.equals(candidate.name())) {
                if (expected != null) throw new AssertionError("duplicate Termux MCP catalog entry");
                expected = candidate;
            }
        }
        if (expected == null || !"mcp".equals(expected.kind())) throw new AssertionError("Termux MCP tool is not enabled");
        JSONObject commandSchema = new JSONObject(expected.inputSchema()).optJSONObject("properties");
        String commandField = null;
        if (commandSchema != null) for (String field : List.of("cmd", "command")) {
            JSONObject property = commandSchema.optJSONObject(field);
            if (property != null && "string".equals(property.optString("type"))) { commandField = field; break; }
        }
        if (commandField == null)
            throw new AssertionError("Termux tool has no string command parameter");

        Object registry = NativePluginTools.registry(context, loader);
        if (registry == null) throw new AssertionError("real host ToolRegistry is unavailable");
        CompletableFuture<Object> definitionsDone = new CompletableFuture<>();
        Object raw = invoke(registry, HostCompatibility.choose("getToolDefinitions", "getAll"),
            continuation(loader, definitionsDone));
        Object exported = await(loader, raw, definitionsDone);
        if (!(exported instanceof List<?> definitions)) throw new AssertionError("host definitions are not a list");
        Object registered = Reflector.getObjectField(registry, "tools");
        if (!(registered instanceof Map<?, ?> tools)) throw new AssertionError("host tool map is unavailable");
        Object tool = tools.get(remoteName);
        verifyTool(tool, definitions, expected);

        // Broadcast callers supply only the run identity, never a command or a file path.
        String command = "printf 'MIAIPLUS_TERMUX_HOST_" + nonce + "\\n'; printf 'UID=%s;\\n' \"$(id -u)\"; "
            + "printf 'HOME='; printf '%s\\n' \"$HOME\"; printf 'SHELL='; command -v sh";
        JSONObject request = new JSONObject().put(commandField, command);
        if (commandSchema.has("timeout")) request.put("timeout", 10);
        Object result = execute(loader, tool, request);
        if (isFailure(loader, result)) throw new AssertionError("Termux native MCP proxy failed");
        String text = resultText(loader, result);
        if (!text.contains("MIAIPLUS_TERMUX_HOST_" + nonce) || !text.contains("UID=" + expectedUid + ";")
            || !text.contains("com.termux/files/home") || !text.contains("com.termux/files/usr/bin/sh"))
            throw new AssertionError("Termux MCP result did not match the owned app shell identity");
        if (Boolean.TRUE.equals(invoke(result, "isDirectAnswer"))) throw new AssertionError("MCP tool became a direct answer");
        Object images = invoke(result, "getImages");
        if (images instanceof List<?> list && !list.isEmpty()) throw new AssertionError("Termux MCP acquired Phone Use images");
        Bundle after = screenState(context);
        for (String key : List.of("screen_task", "native_session", "result")) {
            if (!java.util.Objects.equals(before.getString(key), after.getString(key)))
                throw new AssertionError("Termux MCP changed screen-task data");
        }
        if (before.getBoolean("active") != after.getBoolean("active")
            || before.getBoolean("completed") != after.getBoolean("completed"))
            throw new AssertionError("Termux MCP changed screen-task lifecycle");
        return "real host registry and current-module proxy; unique definition and matching command schema; "
            + "Termux shell nonce and UID verified; no screen task";
    }

    private static void verifyTool(Object tool, List<?> definitions, PluginToolSpec spec) throws Exception {
        if (tool == null || !Proxy.isProxyClass(tool.getClass())) throw new AssertionError("native proxy missing: " + spec.name());
        Object handler = Proxy.getInvocationHandler(tool);
        if (handler.getClass().getClassLoader() != PluginPlatformProbe.class.getClassLoader()
            || !handler.getClass().getName().startsWith(NativePluginTools.class.getName() + "$$"))
            throw new AssertionError("tool belongs to another module generation: " + spec.name());
        if (!spec.name().equals(invoke(tool, "getName"))) throw new AssertionError("tool name differs from catalog");
        JSONObject schema = new JSONObject(spec.inputSchema());
        if (!jsonValue(schema).equals(jsonValue(new JSONObject(String.valueOf(invoke(tool, "getParameters"))))))
            throw new AssertionError("executable schema differs from catalog: " + spec.name());
        List<Object> matches = new java.util.ArrayList<>();
        for (Object value : definitions) if (spec.name().equals(invoke(value, "getName"))) matches.add(value);
        if (matches.size() != 1) throw new AssertionError("exported definition missing or duplicated: " + spec.name());
        if (!jsonValue(schema).equals(jsonValue(new JSONObject(String.valueOf(invoke(matches.get(0), "getParameters"))))))
            throw new AssertionError("model schema differs from executable tool: " + spec.name());
        if (!java.util.Objects.equals(invoke(matches.get(0), "getDescription"), invoke(tool, "getDescription")))
            throw new AssertionError("model description differs from executable tool: " + spec.name());
        String category = "builtin".equals(spec.kind()) ? "phone" : "plugin";
        if (!category.equals(invoke(tool, "getCategory"))) throw new AssertionError("wrong plugin category: " + spec.name());
    }

    private static String phone(Context context, ClassLoader loader, Map<?, ?> tools, String backend) throws Exception {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new AssertionError("unlock the owned fixture first");
        String name = "foreground".equals(backend) ? PhoneUseRouting.FOREGROUND_TOOL : PhoneUseRouting.BACKGROUND_TOOL;
        Object tool = tools.get(name);
        String session = null;
        try {
            String goal = "foreground".equals(backend)
                ? "只观察 MiAIPlus Pluginify 自有测试页面，等待一次后结束，不操作其他应用"
                : "只观察后台独立虚拟屏的空白页面，等待一次后结束，不启动或操作任何应用，不切换前台";
            Object started = execute(loader, tool, new JSONObject().put("goal", goal));
            // Retain an admitted session before asserting its image/backend, so failed assertions still finish it.
            if (!isFailure(loader, started)) session = session(resultText(loader, started));
            String first = phoneObservation(loader, started);
            verifyBackend(first, backend, name);
            String next = phoneObservation(loader, execute(loader, tool, step(session, "wait")));
            if (!session.equals(session(next))) throw new AssertionError("Phone Use continuation changed session");
            verifyBackend(next, backend, name);
            Object finished = execute(loader, tool, step(session, "finish"));
            if (isFailure(loader, finished) || !resultText(loader, finished).contains("模型报告任务完成"))
                throw new AssertionError("Phone Use finish failed: " + resultText(loader, finished));
            session = null;
            if (screenState(context).getBoolean("active")) throw new AssertionError("finished Phone Use screen task remains active");
            return backend + " screenshot, same-tool continuation and finish"
                + ("foreground".equals(backend) ? "; owned physical fixture" : "; empty virtual screen only; no launch or fallback");
        } finally {
            if (session != null) execute(loader, tool, step(session, "finish"));
        }
    }

    private static void verifyBackend(String text, String backend, String name) {
        if (!text.contains("执行屏幕=" + backend + "；会话工具=" + name))
            throw new AssertionError("Phone Use used the wrong backend or tool; no fallback is permitted");
        if ("foreground".equals(backend) && !(text.contains("前台=" + BuildConfig.APPLICATION_ID + "；")
                || text.contains("前台=" + BuildConfig.APPLICATION_ID + "/")))
            throw new AssertionError("Phone Use did not observe the owned package on the physical screen");
    }

    private static JSONObject step(String session, String action) throws Exception {
        JSONObject value = new JSONObject().put("action", action);
        if ("wait".equals(action)) value.put("duration_ms", 150);
        if ("finish".equals(action)) value.put("message", "Plugin 平台自有页面验证结束");
        return new JSONObject().put("session", session).put("action", value);
    }

    private static String phoneObservation(ClassLoader loader, Object result) {
        String text = resultText(loader, result);
        if (isFailure(loader, result) || !text.startsWith("CONTINUE"))
            throw new AssertionError("Phone Use failed without fallback: " + compact(text));
        Object attached = invoke(result, "getImages");
        if (!(attached instanceof List<?> images) || images.isEmpty()) throw new AssertionError("Phone Use observation has no attached screenshot");
        if (Boolean.TRUE.equals(invoke(result, "isDirectAnswer"))) throw new AssertionError("Phone Use continuation ended the native turn");
        return text;
    }

    private static String session(String text) {
        var matcher = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(text);
        if (!matcher.find()) throw new AssertionError("Phone Use observation has no session");
        return matcher.group(1);
    }

    private static Object execute(ClassLoader loader, Object tool, JSONObject request) throws Exception {
        CompletableFuture<Object> completed = new CompletableFuture<>();
        return await(loader, invoke(tool, "execute", NativePluginTools.json(loader, request.toString()), null,
            continuation(loader, completed)), completed);
    }

    private static boolean isFailure(ClassLoader loader, Object result) {
        return Reflector.findClass("com.aios.osbot.tools.ToolResult$Failure", loader).isInstance(result);
    }

    private static String resultText(ClassLoader loader, Object result) {
        return String.valueOf(invoke(result, isFailure(loader, result) ? "getError" : "getResult"));
    }

    private static Object continuation(ClassLoader loader, CompletableFuture<Object> future) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)},
            (proxy, method, args) -> {
                if ("getContext".equals(method.getName())) return Reflector.getStaticObjectField(
                    Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
                if ("resumeWith".equals(method.getName())) { future.complete(args[0]); return null; }
                return "plugin-platform-continuation";
            });
    }

    private static Object await(ClassLoader loader, Object value, CompletableFuture<Object> completion) throws Exception {
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader),
            "getCOROUTINE_SUSPENDED");
        Object result = value == suspended ? completion.get(70, TimeUnit.SECONDS) : value;
        Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", result);
        return result;
    }

    private static Bundle screenState(Context context) {
        Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
        if (state == null) throw new AssertionError("screen state unavailable");
        return state;
    }

    private static Object jsonValue(Object value) throws Exception {
        if (value instanceof JSONObject object) {
            Map<String, Object> normalized = new java.util.TreeMap<>();
            var keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                normalized.put(key, jsonValue(object.get(key)));
            }
            return normalized;
        }
        if (value instanceof JSONArray array) {
            List<Object> normalized = new java.util.ArrayList<>();
            for (int index = 0; index < array.length(); index++) normalized.add(jsonValue(array.get(index)));
            return normalized;
        }
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    private static Object invoke(Object object, String method, Object... args) { return Reflector.callMethod(object, method, args); }
    private static String compact(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').substring(0, Math.min(value.length(), 350));
    }
    private static void report(Context context, String text) {
        Bundle report = new Bundle(); report.putString("report", text);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, report);
    }
    private PluginPlatformProbe() { }
}
