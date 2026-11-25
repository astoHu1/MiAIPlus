package io.github.miaiplus;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeRoutingTest {
    @After public void cleanup() { NativeVoiceRoutingHook.dispose(); }

    @Test public void onlyExplicitBackgroundActionsReachTheBackgroundTools() {
        String[] actions = {
            "请在后台打开浏览器搜索\"不要后台操作是什么意思\"",
            "后台帮我打开酷安搜索 MiAIPlus",
            "用虚拟屏幕查看应用页面，不要操作我的主屏",
            "使用 miaiplus_background_phone_use 帮我完成这个任务",
            "OPEN Calculator on a VIRTUAL DISPLAY",
            "请在后台打开计算器实际输入137乘29，不要仅解释操作步骤",
            "用虚拟屏查看设置，告诉我为什么打不开",
            "请在后台查询计算器的结果，不用解释为什么",
            "Use the virtual screen to type what is a virtual display into the search box"
        };
        for (String request : actions) assertTrue(request, NativeChatRouting.requestsBackgroundTools(request));
        String[] ordinary = {
            "你好", "打开计算器", "在后台播放音乐", "帮我后台下载文件", "什么是虚拟屏", "如何在后台打开应用",
            "miaiplus_background_phone_use 是什么意思", "不要后台操作，打开计算器就行", "不用虚拟屏打开应用",
            "How do I open apps in the background?", "Play music in the background",
            "Do not use the virtual screen to open Calculator", "请问虚拟屏是否支持输入",
            "请帮我解释后台控屏怎么操作", "  \nPlease explain virtual display input",
            "Does virtual screen support opening Calculator?", ""
        };
        for (String request : ordinary) assertFalse(request, NativeChatRouting.requestsBackgroundTools(request));
        assertFalse(NativeChatRouting.requestsBackgroundTools(null));
    }

    @Test public void onlyForegroundAppActionsAndControlFollowupsReachTheForegroundTool() {
        String[] actions = {
            "去抖音打开这个沙雕视频的原视频", "帮我在微信里搜索这个联系人", "你用控屏自己操作吧",
            "使用 miaiplus_phone_use 完成刚才的任务", "打开浏览器，搜索这张图的出处",
            "请在微信搜索‘通过这种方法找到答案’", "用控屏按这个方法操作",
            "Find this video in Douyin", "Please control my phone and finish the task"
        };
        for (String request : actions)
            assertEquals(request, PhoneUseRouting.FOREGROUND_TOOL, NativeChatRouting.requestedPhoneTool(request));
        String[] ordinary = {
            "你好", "打开抖音", "搜索原视频", "如何去抖音找到原视频", "怎么用控屏自己操作",
            "miaiplus_phone_use 是什么意思", "不要用控屏自己操作", "你支持控屏吗", "请用这个方法找到答案",
            "请你不用控屏自己操作", "请告诉我怎么去抖音打开原视频", "How do I control my phone?",
            "Do not control my phone", "在后台播放音乐", ""
        };
        for (String request : ordinary) assertEquals(request, "", NativeChatRouting.requestedPhoneTool(request));
        assertEquals("", NativeChatRouting.requestedPhoneTool(null));
    }

    @Test public void decisionsKeepFixedRulesEtaDelegationAndBackgroundOverridesForeground() {
        assertEquals(PhoneUseRouting.BACKGROUND_TOOL,
            NativeChatRouting.requestedPhoneTool("用后台控屏去抖音打开这个视频"));
        assertEquals(new NativeChatRouting.Decision("", "blank"), NativeChatRouting.decide(null));
        assertEquals(new NativeChatRouting.Decision("", "explanation"), NativeChatRouting.decide("如何在后台打开应用"));
        assertEquals(new NativeChatRouting.Decision("", "no_match"), NativeChatRouting.decide("你好 private-query"));
        assertEquals(new NativeChatRouting.Decision(PhoneUseRouting.FOREGROUND_TOOL, "phone_control"),
            NativeChatRouting.decide("用控屏自己操作 private-query"));
        assertEquals(new NativeChatRouting.Decision(PhoneUseRouting.BACKGROUND_TOOL, "background_action"),
            NativeChatRouting.decide("在后台打开计算器 private-query"));
        for (String request : new String[]{"让 Eta 统计一下今天哪些应用给我发的通知最多", "交给eta处理：整理本周日程",
            "请伊塔帮我调研一下这三款手机", "Ask Eta to summarize my notifications"}) {
            assertEquals(request, new NativeChatRouting.Decision(EtaAgent.RUN_TOOL, "eta_delegation"),
                NativeChatRouting.decide(request));
        }
        for (String request : new String[]{"什么是 Eta", "外卖的 ETA 是多少", "beta 版什么时候发布",
            "What is ETA for my delivery", "让我看看今天的新闻"}) {
            assertEquals(request, "", NativeChatRouting.decide(request).tool());
        }
    }

    @Test public void routeScopeBelongsToItsCallbackThreadAndInnermostScopeAndEndsOnFailure() throws Exception {
        Object callback = new Object();
        Object otherCallback = new Object();
        try (NativeChatRouting.Scope ignored = NativeChatRouting.begin(callback, true)) {
            try (NativeChatRouting.Scope ordinary = NativeChatRouting.begin(callback, false)) {
                assertFalse(NativeChatRouting.forcesAgent(callback));
            }
            assertTrue(NativeChatRouting.forcesAgent(callback));
            assertFalse(NativeChatRouting.forcesAgent(otherCallback));
            assertFalse(CompletableFuture.supplyAsync(() -> NativeChatRouting.forcesAgent(callback)).get());
            try (NativeChatRouting.Scope inner = NativeChatRouting.begin(otherCallback, true)) {
                assertTrue(NativeChatRouting.forcesAgent(otherCallback));
                assertFalse(NativeChatRouting.forcesAgent(callback));
            }
            assertTrue(NativeChatRouting.forcesAgent(callback));
        }
        assertFalse(NativeChatRouting.forcesAgent(callback));
        assertFalse(NativeChatRouting.forcesAgent(otherCallback));
        try {
            try (NativeChatRouting.Scope ignored = NativeChatRouting.begin(callback, true)) {
                throw new IllegalStateException("host submission failed");
            }
        } catch (IllegalStateException expected) {
            assertEquals("host submission failed", expected.getMessage());
        }
        assertFalse(NativeChatRouting.forcesAgent(callback));
    }

    @Test public void languageRouteOnlyHandsOffActionRequestsOutsideSimplifiedChinese() {
        for (Locale locale : new Locale[]{Locale.US, Locale.TAIWAN, Locale.JAPAN, Locale.forLanguageTag("ar-EG")}) {
            assertTrue(locale.toString(), NativePhoneLanguageRouting.candidate(locale, "打开计算器"));
            assertTrue(locale.toString(), NativePhoneLanguageRouting.candidate(locale, "請打開計算機"));
            assertTrue(locale.toString(), NativePhoneLanguageRouting.candidate(locale, "Please open Calculator"));
        }
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.SIMPLIFIED_CHINESE, "打开计算器"));
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.forLanguageTag("zh-Hans-CN"), "打开计算器"));
        assertFalse(NativePhoneLanguageRouting.candidate((Locale)null, "打开计算器"));
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.US, null));
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.US, "  \n "));
        for (String query : new String[]{"你好", "设定明天七点闹钟", "如何打开计算器", "不要用控屏自己操作",
            "Do not control my phone", "Explain how to open Calculator", "在后台播放音乐"}) {
            assertFalse(query, NativePhoneLanguageRouting.candidate(Locale.US, query));
        }
    }

    @Test public void onlyTheCurrentMainVoiceRequestCanBeHandedOff() {
        Manager manager = new Manager();
        assertTrue(owns(manager, "current"));
        manager.a.get("main").engine.a.requests.put("old", State.VAD_END);
        assertFalse(owns(manager, "old"));
        assertFalse(owns(manager, "unknown"));
        assertFalse(owns(manager, null)); // MiClaw's own ASR Final has no id.
        assertFalse(owns(manager, ""));
        Request request = manager.a.get("main").g;
        request.event = null;
        request.duplex = new Event("current");
        assertTrue("duplex speech uses its own recognition event", owns(manager, "current"));
        request.duplex = new Event("other");
        assertFalse(owns(manager, "current"));
        request.duplex = new Event("current");
        request.mode = Mode.TEXT;
        assertFalse(owns(manager, "current"));
        request.mode = Mode.VOICE;

        // Cancelled, finished and foreign-engine requests cannot restart tools.
        Task main = manager.a.get("main");
        main.engine.a.requests.put("current", State.FINISH);
        assertFalse(owns(manager, "current"));
        main.engine.a.requests.clear();
        assertTrue("real ASR does not have to populate the legacy request-state map", owns(manager, "current"));
        main.engine.a.speech.requesting = false;
        assertFalse("a cancelled engine cannot hand off a retained request event", owns(manager, "current"));
        main.engine.a.speech.requesting = true;
        main.engine.a.speech.main = false;
        assertFalse("a foreign engine cannot borrow the registered main task event", owns(manager, "current"));
        main.engine.a.speech.main = true;
        manager.a.put("miclaw_v2", new Task());
        assertTrue(owns(manager, "current"));
        main.engine.a.requests.put("current", State.VAD_END);
        main.g = null;
        assertFalse(owns(manager, "current"));
    }

    @Test public void duplicateFinalsAreScopedToTheirManagerAndClearedOnReload() {
        Manager manager = new Manager();
        assertTrue(NativeVoiceRoutingHook.claim(manager, "current"));
        assertTrue(NativeVoiceRoutingHook.wasClaimed(manager, "current"));
        assertFalse(NativeVoiceRoutingHook.claim(manager, "current"));
        assertTrue(NativeVoiceRoutingHook.claim(new Manager(), "current"));
        NativeVoiceRoutingHook.dispose();
        assertFalse(NativeVoiceRoutingHook.wasClaimed(manager, "current"));
        assertTrue(NativeVoiceRoutingHook.claim(manager, "current"));
    }

    @Test public void ownedCancellationStopsOnlyItsOwnRecording() {
        Manager manager = new Manager();
        Task main = manager.a.get("main");
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        queue.add(() -> assertTrue(cancel(manager, main, "current")));
        queue.add(() -> {
            main.g = new Request();
            main.g.event = new Event("following");
        });
        queue.add(() -> assertFalse("a queued stale cancellation cannot stop the new recording",
            cancel(manager, main, "current")));
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals("following", main.g.event.getId());
        assertEquals(1, main.stops);
        assertEquals(1, main.intercepts);

        Task old = manager.a.put("main", new Task());
        assertFalse(cancel(manager, old, "current"));
        assertEquals(1, old.stops);
        assertEquals(0, manager.a.get("main").stops);
    }

    @Test public void pendingStopReleasesReloadExactlyOnceWhetherExpiredOrRun() {
        AtomicInteger releases = new AtomicInteger();
        NativeVoiceRoutingHook.PendingStop pending = new NativeVoiceRoutingHook.PendingStop(releases::incrementAndGet);
        assertTrue(pending.expire());
        assertFalse(pending.start());
        assertFalse(pending.expire());
        pending.finish();
        assertEquals(1, releases.get());

        AtomicInteger running = new AtomicInteger();
        NativeVoiceRoutingHook.PendingStop started = new NativeVoiceRoutingHook.PendingStop(running::incrementAndGet);
        assertTrue(started.start());
        assertFalse("timeout cannot release an already running cancellation", started.expire());
        assertEquals(0, running.get());
        started.finish();
        started.finish();
        assertFalse(started.expire());
        assertEquals(1, running.get());
    }

    private static boolean cancel(Manager manager, Task main, String id) {
        return NativeVoiceRoutingHook.cancelOwnedTask(manager, main, id, "event", "duplex", "mode");
    }

    private static boolean owns(Manager manager, String id) {
        return NativeVoiceRoutingHook.ownsMainVoiceRequest(manager, id, "event", "duplex", "mode");
    }

    enum Mode { VOICE, TEXT }
    enum State { VAD_END, FINISH }
    static final class Manager {
        final Map<String, Task> a = new HashMap<>(Map.of("main", new Task()));
    }
    static final class Task {
        Request g = new Request();
        final Engine engine = new Engine();
        int stops, intercepts;
        public Engine getEngine() { return engine; }
        public void interceptAndCancelDialogOperation() { intercepts++; }
        public void G(boolean stopTts, int reason) { stops++; g = null; }
        public void forceStop(boolean stopTts, int reason) {
            throw new AssertionError("forceStop clears later request callbacks");
        }
    }
    static final class Request {
        Mode mode = Mode.VOICE;
        Event event = new Event("current");
        Event duplex;
    }
    record Event(String id) { public String getId() { return id; } }
    static final class Engine { final Account a = new Account(); }
    static final class Account {
        final Map<String, State> requests = new HashMap<>(Map.of("current", State.VAD_END));
        final Speech speech = new Speech();
        public Map<String, State> getRequestInfo() { return requests; }
        public Speech getSpeechEngine() { return speech; }
    }
    static final class Speech {
        boolean main = true, requesting = true;
        public boolean isMainEngine() { return main; }
        public boolean isRequesting() { return requesting; }
    }
}
