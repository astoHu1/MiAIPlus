package io.github.miaiplus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Debug-only live official-model test. Observes real calls; never supplies a model answer or action. */
final class NativeBackgroundModelProbe {
    private static final String MARKER = "MIAIPLUS_BACKGROUND_NATIVE_TEST";
    private static volatile boolean canceled;
    private static volatile CountDownLatch isolation;
    private static volatile Trace activeTrace;
    private static volatile String screenshotNonce;
    private static volatile CountDownLatch screenshotReady;
    private NativeBackgroundModelProbe() { }

    static void signalReady() { CountDownLatch value = isolation; if (value != null) value.countDown(); }
    static void signalCancel() { canceled = true; signalReady(); }
    static void signalPixels(String nonce, String taskRef, String sessionRef, String frameRef, int step) {
        Trace trace = activeTrace;
        if (trace != null) trace.pixels(nonce, taskRef, sessionRef, frameRef, step);
    }
    static void signalScreenshot(String nonce) {
        CountDownLatch ready = screenshotReady;
        if (ready != null && nonce != null && nonce.equals(screenshotNonce)) ready.countDown();
    }

    static String run(Context context, ClassLoader loader) throws Exception {
        return run(context, loader, false);
    }

    static String run(Context context, ClassLoader loader, boolean voice) throws Exception {
        check(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(), "unlock device first");
        Bundle config = NativeModelHook.requestConfig(context);
        check(!config.getBoolean("globalModel"), "official model required");
        Bundle busy = context.getContentResolver().call(BridgeProvider.URI, "conversation_settings", null, null);
        Bundle pluginBusy = context.getContentResolver().call(BridgeProvider.URI, "plugin_busy", null, null);
        check(busy != null && !busy.getBoolean("screenBusy") && pluginBusy != null && !pluginBusy.getBoolean("active"),
            "another screen/plugin task is active");
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("pe1.q", loader));
        Object local = Reflector.getObjectField(Reflector.getObjectField(Reflector.callMethod(entry, "getTopicByIdUseCase"), "a"), "a");
        Intent original = ConversationReturn.capture(context, loader);
        if (original == null) original = ConversationReturn.route(context, Reflector.newInstance(
            Reflector.findClass("com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), null, "quick"));
        String topic = "miaiplus-background-native-" + UUID.randomUUID();
        String chat = "miaiplus-background-native-" + UUID.randomUUID();
        Object type = Reflector.getStaticObjectField(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicType", loader), "MAIN_AGENT_SESSION");
        Object group = Reflector.newInstance(Reflector.findClass("com.miui.voiceassist.model.topicgroup.TopicGroup", loader),
            topic, "MiAIPlus native background test", System.currentTimeMillis(), false, 0, null, "osbot.main", chat,
            null, Reflector.callMethod(type, "getRaw"), true);
        Object ids = Reflector.getStaticObjectField(Reflector.findClass("ck.a", loader), "a");
        String identity = (String)Reflector.callMethod(ids, "buildAgentSubId", "osbot.main", chat);
        canceled = false;
        Throwable failure = null;
        Trace trace = new Trace(context, loader, identity);
        activeTrace = trace;
        try {
            suspend(loader, continuation -> Reflector.callMethod(local, "insertConversationGroup", group, continuation));
            Object execution = helper("executionContext", loader, chat);
            Intent route;
            try (var ignored = ConversationReturn.bind(execution)) { route = ConversationReturn.capture(context, loader); }
            check(route != null, "owned route unavailable");
            helper("open", context, route);
            helper("assertChat", context, loader, chat, topic);
            String query = MARKER + "。这是安全的后台控屏验收。请使用后台独立虚拟屏，先在计算器里清屏，再实际输入137乘29，"
                + "读取计算器显示的结果，然后调用finish结束后台任务并回复读到的结果。必须实际操作计算器，不能心算代替，"
                + "只操作计算器，不要打开其他应用、不要切换到主屏。不要仅解释操作步骤。";
            String submission;
            if (voice) submission = NativeVoiceSubmissionProbe.submit(context, loader, query);
            else {
                MainThread.call(() -> {
                    Object callback = HookSmokeProbe.chatCallbackForProbe();
                    check(callback != null, "native chat callback unavailable");
                    Reflector.callMethod(callback, "onSendClick", query, query);
                    return null;
                });
                submission = "actual onSendClick";
            }
            report(context, "NATIVE_BACKGROUND_SUBMITTED " + submission);
            long deadline = SystemClock.elapsedRealtime() + 600_000;
            long noEntryDeadline = SystemClock.elapsedRealtime() + 35_000;
            long lastReport = 0;
            while (SystemClock.elapsedRealtime() < deadline && !canceled) {
                check(trace.problem == null, trace.problem == null ? "" : trace.problem + "; " + trace.summary());
                if (trace.complete()) return "PROBE PASS: " + submission + "; official model; calculator result verified from owned observation; actual finish; final reply not asserted; " + trace.summary();
                if (SystemClock.elapsedRealtime() > noEntryDeadline && trace.noModelOrTool())
                    throw new AssertionError("chat never entered observed OSBot model/tool boundary; " + trace.summary());
                if (isolation == null && SystemClock.elapsedRealtime() - lastReport >= 5_000) {
                    report(context, "NATIVE_BACKGROUND_PROGRESS " + trace.summary());
                    lastReport = SystemClock.elapsedRealtime();
                }
                Thread.sleep(100);
            }
            throw new AssertionError((canceled ? "probe canceled; " : "official background task timed out; ") + trace.summary());
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            report(context, "NATIVE_BACKGROUND_CLEANUP " + trace.summary());
            isolation = null;
            Throwable cleanup = null;
            try { helper("cancelOwned", loader, chat); } catch (Exception | Error error) { cleanup = error; }
            try { trace.releaseOwned(context); } catch (Exception | Error error) { cleanup = append(cleanup, error); }
            activeTrace = null;
            trace.close();
            // The normal completion guard suppresses programmatic navigation for ten seconds.
            // Wait for its real state rather than overriding the production guard for screenshots.
            try {
                Object interaction = Reflector.getStaticObjectField(XiaoAiHook.class, "interaction");
                long deadline = SystemClock.elapsedRealtime() + 15_000;
                while (interaction != null && Boolean.TRUE.equals(Reflector.callMethod(interaction, "hostQuiet"))) {
                    check(SystemClock.elapsedRealtime() < deadline, "host completion guard did not settle");
                    Thread.sleep(100);
                }
            } catch (Exception | Error error) { cleanup = append(cleanup, error); }
            try {
                Object execution = helper("executionContext", loader, chat);
                Intent owned;
                try (var ignored = ConversationReturn.bind(execution)) { owned = ConversationReturn.capture(context, loader); }
                if (owned != null) {
                    helper("open", context, owned);
                    helper("assertChat", context, loader, chat, topic);
                    screenshotNonce = UUID.randomUUID().toString();
                    screenshotReady = new CountDownLatch(1);
                    report(context, "NATIVE_BACKGROUND_WAIT_OWNED_SCREEN " + screenshotNonce);
                    screenshotReady.await(12, TimeUnit.SECONDS);
                }
            } catch (Exception | Error error) {
                report(context, "NATIVE_BACKGROUND_DIAGNOSTIC_SCREENSHOT_SKIPPED " + error.getClass().getSimpleName());
            }
            finally { screenshotReady = null; screenshotNonce = null; }
            try {
                helper("open", context, original);
                helper(ConversationContinuityProbe.class, "awaitOriginalRoute", context, loader, original);
            } catch (Exception | Error error) { cleanup = append(cleanup, error); }
            try { suspend(loader, continuation -> Reflector.callMethod(local, "deleteConversationGroup", topic, continuation)); }
            catch (Exception | Error error) { cleanup = append(cleanup, error); }
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup);
                else if (cleanup instanceof Exception exception) throw exception;
                else throw (Error)cleanup;
            }
        }
    }

    private static final class Trace implements AutoCloseable {
        private final List<HookCallback.Unhook> hooks = new ArrayList<>();
        private final HashSet<String> sessions = new HashSet<>();
        private final HashSet<String> names = new HashSet<>();
        private final HashSet<String> clients = new HashSet<>();
        private final HashSet<String> modelConfigs = new HashSet<>();
        private int calls, observations, images, taiyi, external;
        private int controlCalls, cliStarts, cliSteps, cliGoals;
        private int pixelProofs, gateCalls, gateOwned, executedTaps, currentStep = -1;
        private String currentFrameRef = "";
        private int serializedRequests, serializedImages, serializedStep = -1, serializedMatches, serializedMismatches;
        private String serializedLastFrameRef = "", serializedStatus = "unseen";
        private final java.util.Set<Object> seenResults = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final HashSet<String> gateNames = new HashSet<>();
        private final HashSet<String> verifiedNonces = new HashSet<>();
        private boolean finish, observedResult, isolated;
        private String ownedTask;
        private final java.util.ArrayDeque<String> recentActions = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<String> calculatorStates = new java.util.ArrayDeque<>();
        private String lastCalculator = "unseen";
        private long calculatorChangedAt;
        private volatile String route = "unseen";
        volatile String problem;

        Trace(Context context, ClassLoader loader, String identity) throws Exception {
            try {
                hooks.add(Hooks.hookMethod(Reflector.findClass("ll1.f0", loader)
                    .getDeclaredMethod(HostCompatibility.choose("C", "D")), new HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { route = String.valueOf(p.getResult()); }
                }));
                var gate = Reflector.findClass("kj.g", loader).getDeclaredMethod("evalShouldStopAfterTool",
                    Reflector.findClass("hj.e", loader), Reflector.findClass("com.aios.osbot.agent.model.ToolCall", loader),
                    Reflector.findClass("com.aios.osbot.tools.ToolResult", loader), Reflector.findClass("kotlin.coroutines.Continuation", loader));
                hooks.add(Hooks.hookMethod(gate, new HookCallback(1000) {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        String name = String.valueOf(Reflector.callMethod(p.args[1], "getName"));
                        String arguments = String.valueOf(Reflector.callMethod(p.args[1], "getArguments"));
                        if (!ForegroundConsent.isPhoneTool(name, arguments)) return;
                        Object execution = p.args[0] == null ? null : Reflector.callMethod(p.args[0], "getExecutionContext");
                        boolean owned = execution != null && identity.equals(Reflector.callMethod(execution, "getContextVarIdentity"));
                        synchronized (Trace.this) { gateCalls++; gateNames.add(name); if (owned) gateOwned++; }
                        if (!owned) return;
                        accept(context, name, arguments, p.args[2]);
                    }
                }));
                var execute = PhoneUsePlugin.class.getDeclaredMethod("execute", Context.class, ClassLoader.class,
                    PluginToolSpec.class, Object.class, Object.class);
                hooks.add(Hooks.hookMethod(execute, new HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object execution = p.args[4];
                        if (execution == null || !identity.equals(Reflector.callMethod(execution, "getContextVarIdentity"))) return;
                        PluginToolSpec spec = (PluginToolSpec)p.args[2];
                        if (p.hasThrowable()) { problem = "owned typed tool threw " + p.getThrowable().getClass().getSimpleName(); return; }
                        accept(context, spec.name(), String.valueOf(p.args[3]), p.getResult());
                    }
                }));
                for (String name : new String[]{"qm.c", "um.h", "qm.i", "qm.j", "qm.b"}) {
                    Class<?> client = Reflector.findClass(name, loader);
                    for (var method : client.getDeclaredMethods()) {
                        if (name.equals("qm.i") && method.getName().equals("buildOpenAIRequest$llm_client")) {
                            hooks.add(Hooks.hookMethod(method, new HookCallback() {
                                @Override protected void afterHookedMethod(MethodHookParam p) {
                                    if (!(p.args[0] instanceof List<?> messages) || !messages.stream().anyMatch(message -> String.valueOf(
                                        Reflector.callMethod(message, "getContent")).contains(MARKER))) return;
                                    serializedRequest(p.getResult());
                                }
                            }));
                        }
                        if (!method.getName().equals("streamCompletion")) continue;
                        hooks.add(Hooks.hookMethod(method, new HookCallback() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!(p.args[0] instanceof List<?> messages)) return;
                                boolean owned = messages.stream().anyMatch(message -> String.valueOf(
                                    Reflector.callMethod(message, "getContent")).contains(MARKER));
                                if (!owned) return;
                                synchronized (Trace.this) {
                                    clients.add(client.getName());
                                    modelConfigs.add(configSummary(p.args[2]));
                                    if (name.equals("um.h")) taiyi++;
                                    else if (!name.equals("qm.c")) external++;
                                }
                            }
                        }));
                    }
                }
            } catch (Exception | Error error) { close(); throw error; }
        }

        private synchronized void serializedRequest(Object request) {
            // Observe the actual final JSON, without changing requests, flows or model responses.
            try {
                int count = 0;
                String latest = "";
                var messages = new JSONObject(String.valueOf(request)).getJSONArray("messages");
                for (int i = 0; i < messages.length(); i++) {
                    var content = messages.getJSONObject(i).optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        var item = content.getJSONObject(j);
                        if (!item.optString("type").equals("image_url")) continue;
                        String uri = item.getJSONObject("image_url").getString("url");
                        int comma = uri.indexOf(";base64,");
                        count++;
                        latest = comma < 0 ? "non-data-image" : PhoneUseLog.reference(uri.substring(comma + 8));
                    }
                }
                serializedRequests++; serializedImages = count; serializedLastFrameRef = latest; serializedStep = currentStep;
                serializedStatus = currentStep < 0 ? "initial" : currentFrameRef.equals(latest) ? "latest-match" : "latest-mismatch";
                if (currentStep >= 0) { if (serializedStatus.equals("latest-match")) serializedMatches++; else serializedMismatches++; }
            } catch (Exception error) { serializedStatus = "diagnostic-" + error.getClass().getSimpleName(); }
        }

        private void accept(Context context, String name, String arguments, Object resultValue) throws Exception {
            synchronized (this) { if (!seenResults.add(resultValue)) return; }
            synchronized (Trace.this) {
                names.add(name); calls++;
                if (!name.equals(PhoneUseRouting.BACKGROUND_TOOL) && !name.equals(PhoneUseRouting.FOREGROUND_TOOL)) {
                    controlCalls++;
                    if (arguments.contains("MIAIPLUS_START:")) cliStarts++;
                    else if (arguments.contains("MIAIPLUS:")) cliSteps++;
                    else cliGoals++;
                }
            }
            if (PhoneUseRouting.FOREGROUND_TOOL.equals(name)) { problem = "model selected foreground tool"; return; }
            String text;
            try { text = plainResult(String.valueOf(Reflector.callMethod(resultValue, "getResult"))); }
            catch (RuntimeException failed) {
                String error;
                try { error = String.valueOf(Reflector.callMethod(resultValue, "getError")); }
                catch (RuntimeException absent) { error = "unavailable"; }
                problem = "actual phone tool returned failure: " + safeFailure(error) + "; " + argumentShape(arguments);
                return;
            }
            if (text == null) { problem = "actual phone tool result envelope unavailable"; return; }
            boolean first = false;
            synchronized (Trace.this) {
                if (text.startsWith("CONTINUE")) {
                    var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(text);
                    if (session.find()) {
                        sessions.add(session.group(1));
                        Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
                        if (state != null && state.getBoolean("active") && session.group(1).equals(state.getString("native_session"))) {
                            String task = state.getString("screen_task");
                            if (ownedTask != null && !ownedTask.equals(task)) problem = "owned screen task changed";
                            ownedTask = task;
                        } else problem = "observation session does not own current screen task";
                    }
                    else problem = "continuing observation missing session";
                    if (!text.contains("执行屏幕=background；会话工具="))
                        problem = "observation left background backend";
                    observations++;
                    List<?> attached = (List<?>)Reflector.callMethod(resultValue, "getImages");
                    currentFrameRef = "";
                    if (attached != null && !attached.isEmpty()) {
                        images++;
                        currentFrameRef = PhoneUseLog.reference((String)Reflector.callMethod(attached.get(attached.size() - 1), "getBase64Data"));
                    }
                    var step = java.util.regex.Pattern.compile("步数=([0-9]+)/").matcher(text);
                    currentStep = step.find() ? Integer.parseInt(step.group(1)) : -1;
                    // A past correct result cannot validate a later changed calculator observation.
                    observedResult = false;
                    syntheticDiagnostics(text);
                    first = observations == 1;
                }
                if (text.startsWith("模型报告任务完成：")) finish = true;
            }
            if (first) {
                CountDownLatch ready = new CountDownLatch(1); isolation = ready;
                report(context, "NATIVE_BACKGROUND_WAIT_ISOLATION");
                if (!ready.await(20, TimeUnit.SECONDS) || canceled) problem = "main-screen isolation handshake failed";
                else synchronized (Trace.this) { isolated = true; }
                isolation = null;
            } else report(context, "NATIVE_BACKGROUND_PROGRESS " + summary());
        }
        private void syntheticDiagnostics(String text) {
            var action = java.util.regex.Pattern.compile("上步动作=([a-z_]+)；执行状态=([a-z_]+)").matcher(text);
            if (action.find()) {
                recentActions.add(action.group(1) + ":" + action.group(2));
                if ("tap".equals(action.group(1)) && "executed".equals(action.group(2))) executedTaps++;
                while (recentActions.size() > 8) recentActions.removeFirst();
            }
            String tree = calculatorTree(text);
            if (tree == null) return;
            List<String> fields = new ArrayList<>();
            for (String row : tree.split("\n")) {
                var id = java.util.regex.Pattern.compile(" id=com\\.miui\\.calculator:id/([A-Za-z0-9_]+)").matcher(row);
                if (!id.find()) continue;
                String resource = id.group(1);
                String lower = resource.toLowerCase(java.util.Locale.ROOT);
                boolean formula = lower.contains("result") || lower.contains("expression") || lower.contains("formula")
                    || lower.contains("equation") || lower.contains("operation");
                if (!formula && row.contains(" clickable")) continue;
                int start = row.indexOf(" text=");
                if (start < 0) continue;
                int end = row.indexOf(" description=", start);
                String value = row.substring(start + 6, end < 0 ? row.length() : end);
                // This test owns a fixed calculation. Never print arbitrary page text or non-calculator nodes.
                if (value.isBlank() || value.length() > 60 || !value.matches("[0-9.,+−\\-×*/÷=() \\u00a0]+")) continue;
                fields.add(resource + "=" + value);
                if (fields.size() == 6) break;
            }
            String state = fields.isEmpty() ? "no-readable-result-fields" : fields.toString();
            if (!state.equals(lastCalculator)) {
                lastCalculator = state;
                calculatorChangedAt = SystemClock.elapsedRealtime();
                calculatorStates.add(state);
                while (calculatorStates.size() > 5) calculatorStates.removeFirst();
            }
        }
        void releaseOwned(Context context) throws Exception {
            String task;
            synchronized (this) { task = ownedTask; }
            if (task == null) return;
            context.getContentResolver().call(BridgeProvider.URI, "model_finished", task, null);
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            do {
                Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", task, null);
                if (state != null && !state.getBoolean("active")) return;
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("owned screen task cleanup did not settle");
        }
        synchronized void pixels(String nonce, String taskRef, String sessionRef, String frameRef, int step) {
            if (nonce == null || !nonce.matches("[a-f0-9-]{36}") || ownedTask == null || sessions.size() != 1 || executedTaps < 4
                || currentStep < 0 || currentStep != step || !currentFrameRef.equals(frameRef)
                || !PhoneUseLog.reference(ownedTask).equals(taskRef)
                || !PhoneUseLog.reference(sessions.iterator().next()).equals(sessionRef) || !verifiedNonces.add(nonce)) return;
            pixelProofs++;
            observedResult = true;
        }

        synchronized boolean complete() {
            return problem == null && calls >= 3 && observations >= 2 && images == observations && sessions.size() == 1
                && !clients.isEmpty() && finish && observedResult && isolated;
        }
        synchronized boolean noModelOrTool() { return clients.isEmpty() && calls == 0 && controlCalls == 0; }
        synchronized String summary() {
            return "onSendOsbotRoute=" + route + ",globalModel=false,clients=" + clients + ",modelConfigs=" + modelConfigs
                + ",gateCalls=" + gateCalls + ",gateOwned=" + gateOwned + ",gateNames=" + gateNames
                + ",tools=" + names + ",calls=" + calls + ",controlApp=" + controlCalls
                + ",taskRef=" + PhoneUseLog.reference(ownedTask)
                + ",sessionRef=" + (sessions.size() == 1 ? PhoneUseLog.reference(sessions.iterator().next()) : "")
                + ",frameRef=" + currentFrameRef + ",observationStep=" + currentStep
                + ",serializedRequests=" + serializedRequests + ",serializedImages=" + serializedImages
                + ",serializedStep=" + serializedStep + ",serializedLastFrameRef=" + serializedLastFrameRef
                + ",serializedStatus=" + serializedStatus + ",serializedMatches=" + serializedMatches + ",serializedMismatches=" + serializedMismatches
                + ",cliStarts=" + cliStarts + ",cliSteps=" + cliSteps + ",cliGoals=" + cliGoals + ",observations=" + observations + ",images=" + images
                + ",sessions=" + sessions.size() + ",taiyiClientCalls=" + taiyi + ",otherDirectClients=" + external
                + ",finish=" + finish + ",executedTaps=" + executedTaps + ",observed3973=" + observedResult + ",pixelProofs=" + pixelProofs + ",finalReply=not-asserted,isolation=" + isolated
                + ",recentActions=" + recentActions + ",calculatorStates=" + calculatorStates
                + ",calculatorUnchangedSeconds=" + (calculatorChangedAt == 0 ? -1 : (SystemClock.elapsedRealtime() - calculatorChangedAt) / 1000);
        }
        @Override public void close() { for (var hook : hooks) hook.unhook(); hooks.clear(); }
    }

    private static String configSummary(Object config) {
        return configSummary(config, 0);
    }
    private static String configSummary(Object config, int depth) {
        if (config == null || depth > 1) return "unavailable";
        StringBuilder result = new StringBuilder();
        for (var getter : config.getClass().getDeclaredMethods()) {
            String name = getter.getName();
            boolean vision = (depth == 1 || name.toLowerCase(java.util.Locale.ROOT).contains("vision"))
                && (getter.getReturnType() == boolean.class || getter.getReturnType() == Boolean.class);
            if (getter.getParameterCount() != 0 || (!vision && !java.util.Set.of("getModel", "getModelName", "getModelId", "getProvider",
                "getBaseUrl", "getApiUrl", "getCapabilities", "getProviderCompat").contains(name))) continue;
            try {
                Object value = getter.invoke(config);
                String text;
                if (name.equals("getCapabilities") || name.equals("getProviderCompat")) text = "{" + configSummary(value, depth + 1) + "}";
                else if (name.equals("getBaseUrl") || name.equals("getApiUrl")) text = PhoneUseLog.reference(String.valueOf(value));
                else if (value instanceof String string) text = string.matches("[A-Za-z0-9._/-]{1,100}") ? string : "present";
                else if (value instanceof Boolean flag) text = flag.toString();
                else continue;
                if (result.length() > 0) result.append(';');
                result.append(name).append('=').append(text);
            } catch (ReflectiveOperationException ignored) { }
        }
        return result.length() == 0 ? "getter-metadata-unavailable" : result.toString();
    }

    private static String calculatorTree(String text) {
        int foreground = text.lastIndexOf("\n前台=");
        if (foreground < 0) return null;
        String observation = text.substring(foreground);
        if (!observation.startsWith("\n前台=com.miui.calculator；") && !observation.startsWith("\n前台=com.miui.calculator/")) return null;
        var marker = java.util.regex.Pattern.compile("(?:MIAIPLUS_FRAME:[a-f0-9-]{36}|\\[本步截图已附加为原生图片\\])\\n").matcher(observation);
        return marker.find() ? observation.substring(marker.end()) : null;
    }
    private static String plainResult(String text) {
        if (text == null || !text.startsWith("{")) return text;
        try {
            JSONObject value = new JSONObject(text);
            if (value.optInt("__envelope__") == 1) {
                Object data = value.opt("data");
                if (data instanceof String plain) return plain;
                if (data instanceof JSONObject object) value = object;
            }
            return value.optString("type").equals("text") && value.opt("text") instanceof String plain ? plain : null;
        } catch (org.json.JSONException invalid) { return null; }
    }
    private static String safeFailure(String error) {
        StringBuilder result = new StringBuilder();
        for (String known : new String[]{"缺少goal或session/action参数", "控屏工具调用失败", "插件工具不可在主线程执行",
            "插件配置不可用，本次调用未执行", "插件配置已更改或工具已停用，本次调用未执行，不要自动重试", "插件工具参数不完整",
            "MiAIPlus 无障碍未连接或已失效，请开启服务或 root 任务前检查", "已有控屏会话，请先停止", "任务内容为空",
            "任务内容为空或过长", "执行屏幕由所调用的工具决定，请勿在会话内覆盖", "设备锁屏", "DEVICE_LOCKED", "PERMISSION_DENIED",
            "FOCUS_ISOLATION_FAILED", "IME_ISOLATION_FAILED", "BACKEND_DIED", "CAPTURE_FAILED", "本轮控屏已结束"}) {
            if (!error.contains(known)) continue;
            if (result.length() > 0) result.append('|');
            result.append(known);
        }
        return (result.length() == 0 ? "unclassified" : result.toString()) + ",errorChars=" + error.length()
            + ",errorRef=" + PhoneUseLog.reference(error);
    }
    private static String argumentShape(String arguments) {
        try {
            JSONObject value = new JSONObject(arguments);
            List<String> keys = new ArrayList<>();
            for (String key : new String[]{"goal", "session", "action", "coordinate_space", "origin_tool", "background", "execution_mode", "command", "query"})
                if (value.has(key)) keys.add(key + ":" + jsonType(value.opt(key)));
            JSONObject action = value.optJSONObject("action");
            String name = action == null ? "none" : action.optString("action");
            if (!java.util.Set.of("tap", "double_tap", "long_press", "swipe", "type", "launch", "back", "home", "wait", "finish", "take_over", "ask_user").contains(name)) name = "unrecognized";
            return "argumentKeys=" + keys + ",keyCount=" + value.length() + ",action=" + name;
        } catch (org.json.JSONException invalid) { return "argumentJson=invalid,argumentChars=" + arguments.length(); }
    }
    private static String jsonType(Object value) {
        return value == null || value == JSONObject.NULL ? "null" : value instanceof JSONObject ? "object"
            : value instanceof org.json.JSONArray ? "array" : value instanceof String ? "string"
            : value instanceof Boolean ? "boolean" : value instanceof Number ? "number" : "other";
    }
    private static Object helper(String name, Object... args) throws Exception {
        return helper(ReplyActionsProbe.class, name, args);
    }
    private static Object helper(Class<?> owner, String name, Object... args) throws Exception {
        // Share the existing pinned local-topic navigation/cancellation helpers without altering model routing.
        for (var method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            method.setAccessible(true);
            try { return method.invoke(null, args); }
            catch (java.lang.reflect.InvocationTargetException wrapper) {
                if (wrapper.getCause() instanceof Exception error) throw error;
                if (wrapper.getCause() instanceof Error error) throw error;
                throw wrapper;
            }
        }
        throw new NoSuchMethodException(name);
    }
    private interface Call { Object invoke(Object continuation); }
    private static Object suspend(ClassLoader loader, Call call) throws Exception {
        CompletableFuture<Object> completion = new CompletableFuture<>();
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{Reflector.findClass("kotlin.coroutines.Continuation", loader)},
            (proxy, method, args) -> switch (method.getName()) {
                case "getContext" -> Reflector.getStaticObjectField(Reflector.findClass("kotlin.coroutines.EmptyCoroutineContext", loader), "INSTANCE");
                case "resumeWith" -> { completion.complete(args[0]); yield null; }
                case "toString" -> "owned native background probe";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Object value = call.invoke(continuation);
        Object suspended = Reflector.callStaticMethod(Reflector.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", loader), "getCOROUTINE_SUSPENDED");
        Object answer = value == suspended ? completion.get(30, TimeUnit.SECONDS) : value;
        Reflector.callStaticMethod(Reflector.findClass("kotlin.ResultKt", loader), "throwOnFailure", answer);
        return answer;
    }
    private static Throwable append(Throwable previous, Throwable next) { if (previous == null) return next; previous.addSuppressed(next); return previous; }
    private static void report(Context context, String text) {
        Bundle report = new Bundle(); report.putString("report", text);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, report);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
