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
    private static volatile CountDownLatch dualToolsReady;
    static Object chatCallbackForProbe() { return chatCallback.get(); }
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
                if ("MIAIPLUS_REPLY_ACTIONS_CANCEL".equals(intent.getStringExtra("frame"))) {
                    ReplyActionsProbe.signalCancel();
                    return;
                }
                if ("MIAIPLUS_REPLY_ACTIONS_READY".equals(intent.getStringExtra("frame"))) {
                    ReplyActionsProbe.signalReady();
                    return;
                }
                if ("MIAIPLUS_ACTION_CONFIRMATION_CANCEL".equals(intent.getStringExtra("frame"))) {
                    ActionConfirmationProbe.signalCancel();
                    return;
                }
                if ("MIAIPLUS_ACTION_CONFIRMATION_READY".equals(intent.getStringExtra("frame"))) {
                    ActionConfirmationProbe.signalReady();
                    return;
                }
                if ("MIAIPLUS_ROBUST_SCREEN_READY".equals(intent.getStringExtra("frame"))) {
                    RobustScreenProbe.signalReady();
                    return;
                }
                if ("MIAIPLUS_PHONE_USE_TERMINAL_READY".equals(intent.getStringExtra("frame"))) {
                    PhoneUseTerminalProbe.signalReady();
                    return;
                }
                if ("MIAIPLUS_FOREGROUND_CONSENT_READY".equals(intent.getStringExtra("frame"))) {
                    ForegroundConsentProbe.signalReady();
                    return;
                }
                if ("MIAIPLUS_DUAL_TOOLS_READY".equals(intent.getStringExtra("frame"))) {
                    CountDownLatch ready = dualToolsReady;
                    if (ready != null) ready.countDown();
                    return;
                }
                try { io.github.miaiplus.hook.Hooks.enter(); }
                catch (IllegalStateException retiring) { return; }
                // Release the broadcast queue before a long probe sends task-control broadcasts.
                Thread worker = new Thread(() -> {
                    String report;
                    try {
                        String token = intent.getStringExtra("frame");
                        report = "MIAIPLUS_LAUNCH_UI".equals(token) ? launchUi(context, loader)
                            : "MIAIPLUS_FOCUS_UI".equals(token) ? focusUi(context, loader)
                            : "MIAIPLUS_DUAL_TOOLS".equals(token) ? dualTools(context, loader)
                            : "MIAIPLUS_ACTION_CONFIRMATION".equals(token) ? ActionConfirmationProbe.run(context, loader)
                            : "MIAIPLUS_ROBUST_SCREEN".equals(token) ? RobustScreenProbe.run(context, loader)
                            : "MIAIPLUS_PHONE_USE_TERMINAL".equals(token) ? PhoneUseTerminalProbe.run(context, loader)
                            : "MIAIPLUS_CONVERSATION_RETURN".equals(token) ? ConversationReturnProbe.run(context, loader)
                            : "MIAIPLUS_CONVERSATION_CONTINUITY".equals(token) ? ConversationContinuityProbe.run(context, loader)
                            : "MIAIPLUS_REPLY_ACTIONS".equals(token) ? ReplyActionsProbe.run(context, loader)
                            : "MIAIPLUS_FOREGROUND_CONSENT".equals(token) ? ForegroundConsentProbe.run(context, loader)
                            : "MIAIPLUS_TRANSPORT_FAILURE".equals(token) ? transportFailures(loader)
                            : "MIAIPLUS_STOCK_REASONING".equals(token) ? reasoningUi(context, loader, true) : run(context, loader, token);
                    }
                    catch (Throwable e) { report = "PROBE FAIL: " + e; io.github.miaiplus.hook.Hooks.log(e); }
                    try { Bundle b = new Bundle(); b.putString("report", report); context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, b); }
                    finally { workers.remove(Thread.currentThread()); io.github.miaiplus.hook.Hooks.leave(); }
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
    /** Exercises registered native tools, rather than bypassing their routing through Provider calls. */
    private static String dualTools(Context context, ClassLoader loader) throws Exception {
        if (context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())
            throw new AssertionError("unlock device for dual-tool test");
        Object registry = NativePhoneUseTool.registry(context, loader);
        if (registry == null) throw new AssertionError("native tool registry was not initialized");
        String foregroundName = "miaiplus_phone_use", backgroundName = "miaiplus_background_phone_use";
        Object foreground = invoke(registry, HostCompatibility.choose("get", "peek"), foregroundName), background = invoke(registry, HostCompatibility.choose("get", "peek"), backgroundName);
        if (foreground == null || background == null || foreground == background)
            throw new AssertionError("both distinct native tools must be registered");
        CompletableFuture<Object> definitionsDone = new CompletableFuture<>();
        List<?> definitions = (List<?>)await(loader, invoke(registry, HostCompatibility.choose("getToolDefinitions", "getAll"), continuation(loader, definitionsDone)), definitionsDone);
        for (String name : List.of(foregroundName, backgroundName)) {
            List<?> matches = definitions.stream().filter(d -> name.equals(invoke(d, "getName"))).collect(java.util.stream.Collectors.toList());
            if (matches.size() != 1) throw new AssertionError("model definition missing or duplicated: " + name);
            Object tool = invoke(registry, HostCompatibility.choose("get", "peek"), name);
            if (!String.valueOf(invoke(matches.get(0), "getParameters")).equals(String.valueOf(invoke(tool, "getParameters"))))
                throw new AssertionError("exported schema differs from executable tool: " + name);
        }
        String foregroundSession = null, backgroundSession = null;
        try {
            String observed = executePhoneTool(loader, foreground, new org.json.JSONObject().put("goal", "只观察 MiAIPlus 主屏测试页面，不操作其他应用"));
            foregroundSession = phoneSession(observed);
            assertPhoneBackend(observed, "foreground", foregroundName);
            if (!observed.contains("前台=io.github.miaiplus；")) {
                var foregroundPackage = java.util.regex.Pattern.compile("前台=([a-zA-Z0-9_./$]+)").matcher(observed);
                throw new AssertionError("foreground tool did not observe physical fixture; foreground="
                    + (foregroundPackage.find() ? foregroundPackage.group(1) : "unavailable"));
            }
            // Hiding a host Activity can rotate the owned fixture. Refresh after that transition before
            // testing an anchored tap, whose production page-change guard must remain strict.
            String settled = executePhoneTool(loader, foreground, new org.json.JSONObject().put("session", foregroundSession)
                .put("action", new org.json.JSONObject().put("action", "wait").put("duration_ms", 800)));
            if (!foregroundSession.equals(phoneSession(settled))) throw new AssertionError("foreground fixture did not settle");
            String tapped = executePhoneTool(loader, foreground, new org.json.JSONObject().put("session", foregroundSession)
                .put("coordinate_space", "normalized").put("action", new org.json.JSONObject().put("action", "tap")
                    .put("element", new org.json.JSONArray().put(500).put(700))));
            if (!foregroundSession.equals(phoneSession(tapped))) throw new AssertionError("foreground tap lost session");
            awaitDualStage(context, "DUAL_TOOLS_FOREGROUND_STARTED");
            assertWrongPhoneTool(loader, background, foregroundSession);
            String continued = executePhoneTool(loader, foreground, phoneStep(foregroundSession, "wait").put("action",
                new org.json.JSONObject().put("action", "wait").put("duration_ms", 100)));
            if (!foregroundSession.equals(phoneSession(continued)) || !continued.contains("前台=io.github.miaiplus；"))
                throw new AssertionError("foreground session changed after wrong-tool rejection");
            assertPhoneBackend(continued, "foreground", foregroundName);
            String finished = executePhoneTool(loader, foreground, phoneStep(foregroundSession, "finish"));
            if (!finished.contains("模型报告任务完成")) throw new AssertionError("foreground tool did not finish: " + finished);
            foregroundSession = null;

            // Let the module-side runner take its main-screen baseline after foreground control has settled.
            // The acknowledgement comes through the same DUMP-protected debug receiver, never a public IPC.
            awaitDualStage(context, "DUAL_TOOLS_FOREGROUND_DONE");

            String first = executePhoneTool(loader, background, new org.json.JSONObject().put("goal", "只操作后台独立测试页面，验证后结束"));
            backgroundSession = phoneSession(first);
            assertPhoneBackend(first, "background", backgroundName);
            if (!first.contains("屏幕=1080x1920") || first.contains("前台=io.github.miaiplus；"))
                throw new AssertionError("background tool observed the physical screen: " + TaskProgress.compact(first));
            awaitDualStage(context, "DUAL_TOOLS_BACKGROUND_STARTED");
            assertWrongPhoneTool(loader, foreground, backgroundSession);
            String launched = executePhoneTool(loader, background, new org.json.JSONObject().put("session", backgroundSession)
                .put("action", new org.json.JSONObject().put("action", "launch")
                    .put("app", "io.github.miaiplus.test")));
            if (!backgroundSession.equals(phoneSession(launched)) || !phonePackage(launched, "io.github.miaiplus.test"))
                throw new AssertionError("background fixture was not launched on its session: "
                    + launched.substring(Math.max(0, launched.lastIndexOf("前台="))));
            String backgroundSettled = executePhoneTool(loader, background, new org.json.JSONObject().put("session", backgroundSession)
                .put("action", new org.json.JSONObject().put("action", "wait").put("duration_ms", 800)));
            if (!backgroundSession.equals(phoneSession(backgroundSettled)) || !phonePackage(backgroundSettled, "io.github.miaiplus.test"))
                throw new AssertionError("background fixture did not settle");
            // Explicit normalized coordinates avoid guessing the JPEG resize ratio. Both gestures stay inside
            // our empty test page, and the module-side physical fixture records any accidental input leakage.
            int touchDowns = 0;
            for (org.json.JSONObject action : List.of(
                new org.json.JSONObject().put("action", "tap").put("element", new org.json.JSONArray().put(500).put(700)),
                new org.json.JSONObject().put("action", "swipe").put("start", new org.json.JSONArray().put(500).put(800))
                    .put("end", new org.json.JSONArray().put(500).put(500)).put("duration_ms", 300))) {
                String next = executePhoneTool(loader, background, new org.json.JSONObject().put("session", backgroundSession)
                    .put("coordinate_space", "normalized").put("action", action));
                if (!backgroundSession.equals(phoneSession(next)) || !next.contains("屏幕=1080x1920"))
                    throw new AssertionError("background gesture lost its backend/session");
                assertPhoneBackend(next, "background", backgroundName);
                if (!next.contains("touch downs " + ++touchDowns))
                    throw new AssertionError("background gesture was not delivered to its fixture: " + TaskProgress.compact(next));
            }
            String finishedBackground = executePhoneTool(loader, background, phoneStep(backgroundSession, "finish"));
            if (!finishedBackground.contains("模型报告任务完成"))
                throw new AssertionError("background tool did not finish: " + finishedBackground);
            backgroundSession = null;
            return "PROBE PASS: both exported native tool definitions and registry proxies; foreground and background starts; cross-tool continuations rejected without losing original sessions; background fixture tap/swipe and finish";
        } finally {
            dualToolsReady = null;
            if (foregroundSession != null) executePhoneTool(loader, foreground, phoneStep(foregroundSession, "finish"));
            if (backgroundSession != null) executePhoneTool(loader, background, phoneStep(backgroundSession, "finish"));
        }
    }
    private static void awaitDualStage(Context context, String name) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); dualToolsReady = ready;
        Bundle stage = new Bundle(); stage.putString("report", name);
        context.getContentResolver().call(BridgeProvider.URI, "hook_report", null, stage);
        if (!ready.await(20, TimeUnit.SECONDS)) throw new AssertionError("dual-tool stage was not acknowledged: " + name);
        dualToolsReady = null;
    }
    private static org.json.JSONObject phoneStep(String session, String action) throws Exception {
        return new org.json.JSONObject().put("session", session).put("action", new org.json.JSONObject().put("action", action)
            .put("message", "双控屏工具测试结束"));
    }
    private static String phoneSession(String observation) {
        var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        if (!observation.startsWith("CONTINUE") || !session.find())
            throw new AssertionError("native tool returned no continuing session: " + TaskProgress.compact(observation));
        return session.group(1);
    }
    private static void assertWrongPhoneTool(ClassLoader loader, Object other, String session) throws Exception {
        String refused = executePhoneTool(loader, other, phoneStep(session, "wait"));
        if (refused.startsWith("CONTINUE") || !refused.contains("请继续使用启动此会话的工具"))
            throw new AssertionError("cross-tool continuation was not rejected: " + TaskProgress.compact(refused));
    }
    private static void assertPhoneBackend(String observation, String backend, String tool) {
        if (!observation.contains("执行屏幕=" + backend + "；会话工具=" + tool))
            throw new AssertionError("observation has incorrect backend/tool owner: " + TaskProgress.compact(observation));
    }
    private static boolean phonePackage(String observation, String packageName) {
        return observation.contains("前台=" + packageName + "；") || observation.contains("前台=" + packageName + "/");
    }
    private static String executePhoneTool(ClassLoader loader, Object tool, org.json.JSONObject request) throws Exception {
        CompletableFuture<Object> completed = new CompletableFuture<>();
        Object result = await(loader, invoke(tool, "execute", NativePhoneUseTool.json(loader, request.toString()), null,
            continuation(loader, completed)), completed);
        String text = String.valueOf(invoke(result,
            Reflector.findClass("com.aios.osbot.tools.ToolResult$Failure", loader).isInstance(result) ? "getError" : "getResult"));
        if (text.startsWith("CONTINUE")) {
            List<?> images = (List<?>)invoke(result, "getImages");
            if (images == null || images.isEmpty()) throw new AssertionError("native tool observation has no attached image");
            if (Boolean.TRUE.equals(invoke(result, "isDirectAnswer"))) throw new AssertionError("native tool ended its continuing turn");
        }
        return text;
    }
    private static String focusUi(Context context, ClassLoader loader) throws Exception {
        if (context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) throw new AssertionError("unlock device for focus UI test");
        android.app.Activity origin = MainThread.call(HookSmokeProbe::resumedActivity);
        Object manager = MainThread.call(() -> invoke(Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.UiManager", loader), "getInstance", context), "getFloatManager"));
        android.view.View root = MainThread.call(() -> (android.view.View)invoke(manager, "getRootLayout"));
        boolean compact = MainThread.call(() -> root != null && root.isAttachedToWindow() && root.isShown()
            && root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams params
            && params.type >= android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW);
        if (!compact && origin == null) throw new AssertionError("no resumed XiaoAi activity or shortcut overlay; "
            + MainThread.call(() -> root == null ? "root=null" : "attached=" + root.isAttachedToWindow()
                + ",shown=" + root.isShown() + ",params=" + (root.getLayoutParams() == null ? "null"
                    : root.getLayoutParams().getClass().getName())));
        Object service = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
        String observation = String.valueOf(invoke(invoke(service, "controlApp", "只观察测试页面，不操作"), "getText"));
        consumeFrame(context, observation);
        var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        if (!session.find()) throw new AssertionError("no UI session: " + observation.substring(0, Math.min(180, observation.length())));
        try {
            assertFocus(context, "正在操作手机");
            if (compact) {
                if (MainThread.call(root::isShown)) throw new AssertionError("shortcut overlay still covers the operated app");
                var flags = MainThread.call(() -> ((android.view.WindowManager.LayoutParams)root.getLayoutParams()).flags);
                if ((flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) throw new AssertionError("shortcut window still intercepts touches");
            } else if (MainThread.call(HookSmokeProbe::resumedActivity) == origin) throw new AssertionError("XiaoAi origin was not backgrounded");
            // Allow the watcher to poll repeatedly between calls and after two CONTINUE results.
            for (int i = 0; i < 2; i++) {
                Thread.sleep(2200);
                String next = String.valueOf(invoke(invoke(service, "controlApp", "MIAIPLUS:{\"session\":\"" + session.group(1)
                    + "\",\"action\":{\"action\":\"wait\",\"duration_ms\":100,\"intent\":\"检查测试页面" + i + "\"}}"), "getText"));
                if (!next.startsWith("CONTINUE")) throw new AssertionError("continuation failed: " + next.substring(0, Math.min(100, next.length())));
                consumeFrame(context, next);
                Thread.sleep(1000);
                assertFocus(context, "正在操作手机");
                for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                    if (notification.getId() != 0x4d494149) continue;
                    var n = notification.getNotification();
                    var params = new org.json.JSONObject(n.extras.getString("miui.focus.param")).getJSONObject("param_v2");
                    String right = params.getJSONObject("param_island").getJSONObject("bigIslandArea")
                        .getJSONObject("imageTextInfoRight").getJSONObject("textInfo").getString("title");
                    if (!right.contains("检查测试页面" + i)) throw new AssertionError("island intent did not update: " + right);
                    if ((n.flags & android.app.Notification.FLAG_ONLY_ALERT_ONCE) == 0 || params.getBoolean("enableFloat"))
                        throw new AssertionError("intent update floated again");
                }
                if (!compact && MainThread.call(HookSmokeProbe::resumedActivity) == origin)
                    throw new AssertionError("origin restored after a tool step");
            }
            Thread.sleep(6000); // Inspect the expanded task controls on the test device.
        } finally {
            invoke(service, "controlApp", "MIAIPLUS:{\"session\":\"" + session.group(1) + "\",\"action\":{\"action\":\"finish\",\"message\":\"界面观察结束\"}}");
        }
        Thread.sleep(2000);
        if (compact) {
            assertFocus(context, "手机操作已结束");
            if (MainThread.call(root::isShown)) throw new AssertionError("shortcut overlay returned over completion island");
            Thread.sleep(3000); // Capture the completion island before exercising its return action.
            for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                if (notification.getId() == 0x4d494149) notification.getNotification().contentIntent.send();
            Thread.sleep(1500);
            if (MainThread.call(HookSmokeProbe::resumedActivity) == null) throw new AssertionError("native conversation entry did not open");
        }
        else {
            // The task ending must not move the user's screen. Only the focus-island card may appear, and the
            // return happens when the user taps it — not before.
            assertFocus(context, "手机操作已结束");
            if (MainThread.call(HookSmokeProbe::resumedActivity) == origin)
                throw new AssertionError("finishing the task pulled the assistant back to the front");
            Thread.sleep(2500);
            if (MainThread.call(HookSmokeProbe::resumedActivity) == origin)
                throw new AssertionError("the assistant returned to the front after the task ended");
            // The card is the only way back, so it must actually carry the return action.
            boolean offered = false;
            for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                if (notification.getId() != 0x4d494149) continue;
                if (notification.getNotification().contentIntent == null) throw new AssertionError("completion card has no return action");
                notification.getNotification().contentIntent.send();
                offered = true;
            }
            if (!offered) throw new AssertionError("completion card was not posted");
            Thread.sleep(1500);
            if (MainThread.call(HookSmokeProbe::resumedActivity) == null)
                throw new AssertionError("the completion card did not return to the conversation");
        }
        String stopObservation = focusRollover(context, service);
        if (!stopObservation.startsWith("CONTINUE")) throw new AssertionError("stop session failed");
        assertFocus(context, "正在操作手机");
        for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() != 0x4d494149) continue;
            var action = notification.getNotification().extras.getBundle("miui.focus.actions")
                .getParcelable("miaiplus_stop", android.app.Notification.Action.class);
            action.actionIntent.send();
        }
        Thread.sleep(2200);
        Bundle stopped = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
        if (stopped == null || stopped.getBoolean("active") || !stopped.getBoolean("completed"))
            throw new AssertionError("expanded island stop did not end task: " + stopped);
        return "PROBE PASS: focus notification, rapid task rollover, backgrounded origin, " + (compact ? "shortcut overlay hidden, completion island and native conversation entry opened" : "same fullscreen Activity restored");
    }
    private static String focusRollover(Context context, Object service) throws Exception {
        String first = String.valueOf(invoke(invoke(service, "controlApp", "只观察任务切换测试，不操作"), "getText"));
        consumeFrame(context, first);
        var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(first);
        if (!session.find()) throw new AssertionError("rollover session failed");
        Object interaction = Reflector.getStaticObjectField(XiaoAiHook.class, "interaction");
        String previousTask = MainThread.call(() -> (String)Reflector.getObjectField(interaction, "screenTask"));
        Object entry = MainThread.call(() -> Reflector.getObjectField(interaction, "returnIntent"));
        // Hold the UI at its last active observation while the provider finishes the task.
        // This deterministically reproduces a new begin() arriving before the terminal poll.
        var previousWatcher = MainThread.call(() -> {
            var observer = (java.util.concurrent.ScheduledExecutorService)Reflector.getObjectField(interaction, "watcher");
            if (observer == null) throw new AssertionError("rollover watcher missing");
            observer.shutdownNow(); return observer;
        });
        if (!previousWatcher.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("old watcher did not stop");
        MainThread.call(() -> null); // Drain any progress callbacks posted before shutdown.
        invoke(service, "controlApp", "MIAIPLUS:{\"session\":\"" + session.group(1)
            + "\",\"action\":{\"action\":\"finish\",\"message\":\"第一任务结束\"}}");
        String next = String.valueOf(invoke(invoke(service, "controlApp", "只观察停止按钮测试，不操作"), "getText"));
        consumeFrame(context, next);
        Thread.sleep(1500);
        Bundle state = context.getContentResolver().call(BridgeProvider.URI, "screen_state", null, null);
        MainThread.call(() -> {
            Object watcher = Reflector.getObjectField(interaction, "watcher");
            String task = (String)Reflector.getObjectField(interaction, "screenTask");
            if (state == null || !state.getBoolean("active") || previousTask.equals(task)
                || !state.getString("screen_task").equals(task) || watcher == previousWatcher || watcher == null)
                throw new AssertionError("new task did not replace its UI observer: state=" + state
                    + ", previous=" + previousTask + ", current=" + task + ", sameWatcher=" + (watcher == previousWatcher));
            Object nextEntry = Reflector.getObjectField(interaction, "returnIntent");
            if (nextEntry == null || nextEntry.equals(entry))
                throw new AssertionError("rollover did not bind a new task-specific conversation entry");
            return null;
        });
        assertFocus(context, "正在操作手机");
        return next;
    }
    private static void consumeFrame(Context context, String observation) throws Exception {
        // Direct probe calls bypass native TOOL message construction, which normally reads this pipe.
        var token = java.util.regex.Pattern.compile("MIAIPLUS_FRAME:([a-f0-9-]{36})").matcher(observation);
        if (!token.find()) {
            if (observation.startsWith("CONTINUE")) throw new AssertionError("frame token missing");
            return;
        }
        try (var input = context.getContentResolver().openInputStream(
            android.net.Uri.withAppendedPath(BridgeProvider.URI, "frame/" + token.group(1)))) {
            if (input == null) throw new AssertionError("frame pipe missing");
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }
    private static String launchUi(Context context, ClassLoader loader) throws Exception {
        if (context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())
            throw new AssertionError("unlock device for launch test");
        Object service = Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
        String observation = String.valueOf(invoke(invoke(service, "controlApp", "只打开 MiAIPlus 测试应用，不操作其他应用"), "getText"));
        consumeFrame(context, observation);
        var session = java.util.regex.Pattern.compile("会话=([a-f0-9-]{36})").matcher(observation);
        if (!session.find()) throw new AssertionError("launch session not started");
        if (!observation.contains("io.github.miaiplus.test")) throw new AssertionError("test launcher missing from observation");
        if (!observation.contains("前台=com.miui.securitycenter；"))
            throw new AssertionError("start the normal Security app page before the launch probe");
        try {
            String last = "";
            for (String app : new String[]{"io.github.miaiplus.test", "MiAIPlus launch fixture"}) {
                String request = "MIAIPLUS:" + new org.json.JSONObject().put("session", session.group(1))
                    .put("action", new org.json.JSONObject().put("action", "launch").put("app", app).put("intent", "打开启动测试页面"));
                String next = String.valueOf(invoke(invoke(service, "controlApp", request), "getText"));
                if (!next.startsWith("CONTINUE") || !next.contains("前台=io.github.miaiplus.test；"))
                    throw new AssertionError("launch was not verified: " + next.substring(0, Math.min(220, next.length())));
                consumeFrame(context, next);
                var instance = java.util.regex.Pattern.compile("fixture, instance ([0-9]+)").matcher(next);
                if (!instance.find()) throw new AssertionError("launch fixture content missing");
                if (!last.isEmpty() && !last.equals(instance.group(1))) throw new AssertionError("already-open app restarted");
                last = instance.group(1);
            }
        } finally {
            invoke(service, "controlApp", "MIAIPLUS:" + new org.json.JSONObject().put("session", session.group(1))
                .put("action", new org.json.JSONObject().put("action", "finish").put("message", "启动测试结束")));
        }
        return "PROBE PASS: launch from normal Security app page, separate test app focused, package/label resolution, already-open instance preserved";
    }
    private static void assertFocus(Context context, String title) throws Exception {
        for (var notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() != 0x4d494149) continue;
            var extras = notification.getNotification().extras;
            if (!title.contentEquals(extras.getCharSequence("android.title"))) continue;
            var params = new org.json.JSONObject(extras.getString("miui.focus.param")).getJSONObject("param_v2");
            if (title.equals("正在操作手机")) {
                if (!params.getJSONArray("actions").getJSONObject(0).getString("action").equals("miaiplus_stop"))
                    throw new AssertionError("expanded island stop action missing");
                if (extras.getBundle("miui.focus.actions").getParcelable("miaiplus_stop", android.app.Notification.Action.class) == null)
                    throw new AssertionError("expanded island stop PendingIntent missing");
            }
            if (extras.getBundle("miui.focus.pics") != null) return;
        }
        throw new AssertionError("focus notification missing: " + title);
    }
    private static Object currentMode(Object manager) throws Exception {
        for (Method method : manager.getClass().getDeclaredMethods())
            if (method.getName().equals("getCurrentMode") && method.getReturnType().getName().equals(HostCompatibility.name("io1.a"))) return method.invoke(manager);
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
        String reasoningResult = Set.of("gpt-6.1-sol", "qwen3.8-max").contains(settings.getString("model"))
            ? reasoningUi(context, loader, false) : "";
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
        Object config = NativeModelHook.newConfig(loader, "unused-native", 0.0, Integer.valueOf(1024), 1.0,
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
            if (events.stream().noneMatch(e -> e.getClass().getName().equals(HostCompatibility.name("sm.b$f")))) throw new AssertionError("no native tool-call event for " + transport.getClass().getName() + ": " + events);
            assertStreamEnded(events, "TOOL_CALLS");
            Object called = events.stream().filter(e -> e.getClass().getName().equals(HostCompatibility.name("sm.b$f"))).findFirst().orElseThrow();
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
            if (continued.stream().noneMatch(e -> e.getClass().getName().equals(HostCompatibility.name("sm.b$d")) && String.valueOf(invoke(e, "getText")).contains("native-roundtrip-ok")))
                throw new AssertionError("native tool result/image roundtrip failed for " + transport.getClass().getName() + ": " + continued);
            assertStreamEnded(continued, "STOP");
            List<Object> textEvents = collect(loader, transport, List.of(message(loader, "USER", "MIAIPLUS_NATIVE_STREAM_FINAL", null)), List.of(), config);
            if (textEvents.stream().noneMatch(e -> e.getClass().getName().equals(HostCompatibility.name("sm.b$d")) && String.valueOf(invoke(e, "getText")).contains("native-stream-ok")))
                throw new AssertionError("no native text event for " + transport.getClass().getName() + ": " + textEvents);
            assertStreamEnded(textEvents, "STOP");
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
        Object capabilityRaw = HostCompatibility.modern()
            ? invoke(capability, "i", routeConfig, "MIAIPLUS_CAPABILITY_V2_TEST", List.of(), continuation(loader, capabilityDone))
            : invoke(capability, "b", routeConfig, "MIAIPLUS_CAPABILITY_TEST", continuation(loader, capabilityDone));
        Object capabilityResult = await(loader, capabilityRaw, capabilityDone);
        if (HostCompatibility.modern()) {
            Object parsed = invoke(capability, "k", invoke(capabilityResult, "getContent"));
            if (parsed == null || !"fixture intent".equals(invoke(parsed, "getUserIntent"))
                || !List.of("fixture capability").equals(invoke(parsed, "getCapabilities")))
                throw new AssertionError("capability override/schema parsing failed");
        } else if (!"capability-ok".equals(invoke(capabilityResult, "getContent"))) throw new AssertionError("capability override failed");
        Class<?> loggerType = Reflector.findClass("r32.e", loader);
        Object logger = Proxy.newProxyInstance(loader, new Class<?>[]{loggerType}, (p,m,args) -> null);
        Object router = Reflector.newInstance(Reflector.findClass("com.aios.osbot.router.t", loader), http, logger);
        CompletableFuture<Object> routerDone = new CompletableFuture<>();
        Object routerResult = await(loader, invoke(router, "b", routeConfig, "MIAIPLUS_ROUTER_TEST", null, continuation(loader, routerDone)), routerDone);
        Object decision = invoke(routerResult, "getResult");
        if (decision == null || !"FALLBACK".equals(invoke(decision, "getStatus"))) throw new AssertionError("router protocol adaptation failed");
        return "PROBE PASS: actual controlApp hook (lock gate or native observation); stock getCurrentPackage; native images and continuation; Dyn/direct model and memory overrides; SSE text/tools; actual gate, capability and intent router requests plus native response parsing"
            + (reasoningResult.isEmpty() ? "" : "\n" + reasoningResult);
    }
    private static void chatRouting(Context context, ClassLoader loader) throws Exception {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Object callback = chatCallback.get();
                if (callback == null) throw new AssertionError("native text-chat callback unavailable");
                Method route = Reflector.findClass("ll1.f0", loader).getDeclaredMethod(HostCompatibility.choose("C", "D"));
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
                Object fullMode = invoke(viewModel, HostCompatibility.choose("x", "y"));
                List<?> fullItems = (List<?>)invoke(viewModel, HostCompatibility.choose("w", "x"));
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
                } else if (HostCompatibility.modern()) {
                    Object nativePopup = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.d", loader), "access$getCurrentVisible$cp");
                    Object menuPopup = Reflector.getObjectField(nativePopup, "a");
                    if (!Boolean.TRUE.equals(invoke(menuPopup, "isShowing"))) throw new AssertionError("native full-screen effort menu missing");
                    android.view.Menu menu = (android.view.Menu)invoke(menuPopup, "getMenu");
                    var choices = ReasoningPolicy.choices(settings.getString("model"), settings.getString("api"));
                    if (menu.size() != choices.size()) throw new AssertionError("wrong native full-screen efforts");
                    String expected = "responses".equals(settings.getString("api")) ? "high" : "on";
                    for (int i = 0; i < choices.size(); i++)
                        if (!ReasoningPolicy.label(choices.get(i)).contentEquals(menu.getItem(i).getTitle()))
                            throw new AssertionError("wrong native full-screen effort label");
                    Object window = Reflector.getObjectField(menuPopup, "mHyperPopupWindow");
                    Object listener = Reflector.getObjectField(window, "mMenuItemClickListener");
                    invoke(listener, "onMenuItemClick", menu.getItem(0));
                    if (!choices.get(0).equals(NativeModelHook.config(context).getString("reasoning")))
                        throw new AssertionError("native full-screen effort did not persist");
                    invoke(listener, "onMenuItemClick", menu.getItem(choices.indexOf(expected)));
                    if (!expected.equals(NativeModelHook.config(context).getString("reasoning")))
                        throw new AssertionError("native full-screen effort did not restore");
                    invoke(factory, "dismiss");
                    if (currentMode(manager) != before) throw new AssertionError("native full-screen selection overwrote Xiaomi mode");
                    if (!ReasoningPolicy.label(expected).equals(invoke(viewModel, "c0", fullMode))) throw new AssertionError("native full-screen effort label missing");
                } else {
                    android.widget.PopupWindow popup = NativeReasoningHook.activePopup();
                    if (popup == null || !popup.isShowing()) throw new AssertionError("full-screen effort menu missing");
                    android.widget.ListView list = (android.widget.ListView)popup.getContentView();
                    var choices = ReasoningPolicy.choices(settings.getString("model"), settings.getString("api"));
                    String expected = "responses".equals(settings.getString("api")) ? "high" : "on";
                    if (list.getAdapter().getCount() != choices.size()) throw new AssertionError("wrong full-screen efforts");
                    list.performItemClick(null, choices.indexOf(expected), choices.indexOf(expected));
                    if (currentMode(manager) != before) throw new AssertionError("full-screen selection overwrote Xiaomi mode");
                    if (!ReasoningPolicy.label(expected).equals(invoke(viewModel, HostCompatibility.choose("Z", "c0"), fullMode))) throw new AssertionError("full-screen effort label missing");
                }
                floatingReasoningUi(context, loader, anchor, stock, manager, before);
                done.complete("PROBE PASS: native voice-bar, full-screen and floating reasoning entries " + (stock ? "retain stock AUTO/QUICK/EXPERT and labels" : "select and persist model effort without changing stock mode"));
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        String result = done.get(15, TimeUnit.SECONDS);
        if (HostCompatibility.modern() && !stock) {
            fullScreenReasoningMotion(loader);
            result += "; native full-screen forward/reverse animations completed";
        }
        return result;
    }
    private static void fullScreenReasoningMotion(ClassLoader loader) throws Exception {
        Thread.sleep(600); // Let menus from the selection regression finish their native exit animation.
        var events = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        Class<?> animationListener = Reflector.findClass("miuix.popupwidget.widget.n", loader);
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{animationListener}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onForwardAnimationStart", "onReverseAnimationStart" -> events.add(method.getName());
                case "onForwardAnimationEnd" -> { events.add(method.getName()); entered.countDown(); }
                case "onReverseAnimationEnd" -> { events.add(method.getName()); exited.countDown(); }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == args[0]; }
                case "toString" -> { return "MiAIPlus animation probe"; }
                default -> { }
            }
            return null;
        });
        var helpers = new java.util.concurrent.CopyOnWriteArrayList<Object>();
        var hooks = io.github.miaiplus.hook.Hooks.hookAllConstructors(Reflector.findClass("miuix.popupwidget.widget.o", loader), new io.github.miaiplus.hook.HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (helpers.contains(p.thisObject)) return;
                helpers.add(p.thisObject);
                invoke(p.thisObject, "addPopupAnimationListener", listener);
            }
        });
        Object factory = Reflector.getStaticObjectField(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.d", loader), "d");
        try {
            MainThread.call(() -> {
                android.view.View bar = functionBar.get();
                Object model = invoke(bar, "getViewModel");
                invoke(bar, "v", Reflector.newInstance(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.b$b", loader),
                    0, invoke(model, "y"), invoke(model, "x")));
                Object visible = Reflector.callStaticMethod(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.d", loader), "access$getCurrentVisible$cp");
                Object popup = Reflector.getObjectField(visible, "a");
                android.view.Menu menu = (android.view.Menu)invoke(popup, "getMenu");
                Bundle config = NativeModelHook.config(bar.getContext());
                var choices = ReasoningPolicy.choices(config.getString("model"), config.getString("api"));
                int selected = choices.indexOf(config.getString("reasoning"));
                for (int i = 0; i < menu.size(); i++)
                    if (menu.getItem(i).isChecked() != (i == selected))
                        throw new AssertionError("native full-screen selection not restored on reopen");
                if (Boolean.TRUE.equals(invoke(Reflector.getObjectField(popup, "mHyperPopupWindow"), "isFocusable")))
                    throw new AssertionError("native full-screen menu changed keyboard focus behavior");
                return null;
            });
            if (!entered.await(4, TimeUnit.SECONDS)) throw new AssertionError("native full-screen enter animation did not finish: " + events);
            MainThread.call(() -> { invoke(factory, "dismiss"); return null; });
            if (!exited.await(4, TimeUnit.SECONDS)) throw new AssertionError("native full-screen exit animation did not finish: " + events);
            if (!events.containsAll(List.of("onForwardAnimationStart", "onForwardAnimationEnd", "onReverseAnimationStart", "onReverseAnimationEnd")))
                throw new AssertionError("native full-screen animation lifecycle incomplete: " + events);
        } finally {
            MainThread.call(() -> {
                invoke(factory, "dismiss");
                for (Object helper : helpers) invoke(helper, "removePopupAnimationListener", listener);
                for (var hook : hooks) hook.unhook();
                return null;
            });
        }
    }
    private static void floatingReasoningUi(Context context, ClassLoader loader, android.view.View anchor,
        boolean stock, Object manager, Object before) throws Exception {
        Object floating = Reflector.getStaticObjectField(Reflector.findClass("zd1.b0", loader), "a");
        try {
            invoke(floating, "show", anchor, anchor.getRootView());
            Object popup = Reflector.getObjectField(floating, "d");
            android.view.Menu menu = (android.view.Menu)invoke(popup, "getMenu");
            int parentId = NativeReasoningHook.floatingModeItem();
            android.view.MenuItem parent = menu.findItem(parentId);
            if (parent == null || !parent.hasSubMenu()) throw new AssertionError("floating modes missing");
            android.view.SubMenu modes = parent.getSubMenu();
            if (stock) {
                if (modes.size() != 3) throw new AssertionError("stock floating choices changed");
                return;
            }
            Bundle config = NativeModelHook.config(context);
            var choices = ReasoningPolicy.choices(config.getString("model"), config.getString("api"));
            if (modes.size() != choices.size()) throw new AssertionError("floating effort choices missing");
            for (int i = 0; i < choices.size(); i++)
                if (!ReasoningPolicy.label(choices.get(i)).contentEquals(modes.getItem(i).getTitle()))
                    throw new AssertionError("floating effort label mismatch");
            String callback = HostCompatibility.choose("o", "A");
            invoke(floating, callback, modes.getItem(0), anchor.getContext());
            if (!choices.get(0).equals(NativeModelHook.config(context).getString("reasoning")))
                throw new AssertionError("floating effort did not persist");
            invoke(floating, "show", anchor, anchor.getRootView());
            Boolean[] checks = (Boolean[])invoke(floating, HostCompatibility.choose("g", "n"));
            if (checks.length != choices.size() || !checks[0]) throw new AssertionError("floating selection not restored");
            popup = Reflector.getObjectField(floating, "d");
            modes = ((android.view.Menu)invoke(popup, "getMenu")).findItem(parentId).getSubMenu();
            int selected = choices.indexOf(config.getString("reasoning"));
            invoke(floating, callback, modes.getItem(selected), anchor.getContext());
            if (!config.getString("reasoning").equals(NativeModelHook.config(context).getString("reasoning"))
                || currentMode(manager) != before) throw new AssertionError("floating selection changed native mode");
        } finally { invoke(floating, "dismiss"); }
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
    private static void assertStreamEnded(List<Object> events, String reason) {
        long completions = events.stream().filter(e -> e.getClass().getName().equals(HostCompatibility.name("sm.b$b"))).count();
        if (completions != 1 || events.isEmpty()
            || !events.get(events.size() - 1).getClass().getName().equals(HostCompatibility.name("sm.b$b"))
            || !reason.equals(String.valueOf(invoke(events.get(events.size() - 1), "getReason"))))
            throw new AssertionError("native stream did not end with one " + reason + " completion");
        if (events.stream().anyMatch(e -> e.getClass().getName().equals(HostCompatibility.name("sm.b$a"))))
            throw new AssertionError("native stream emitted an error before completion");
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
