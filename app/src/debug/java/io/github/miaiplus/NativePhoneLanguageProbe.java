package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes production submission under the actual host locale; stops at the native agent boundary. */
final class NativePhoneLanguageProbe {
    private NativePhoneLanguageProbe() { }

    static String run(Context context, ClassLoader loader) throws Exception {
        check(!NativeModelHook.requestConfig(context).getBoolean("globalModel"),
            "disable global model temporarily so it cannot mask the language routing under test");
        check(NativePhoneLanguageRouting.candidate(context, "打开计算器"), "host locale must actually be non-zh-CN");
        check(NativePhoneLanguageRouting.available(context), "foreground phone plugin must be enabled");
        String locale = context.getResources().getConfiguration().getLocales().toLanguageTags();
        Object callback = NativeChatRoutingHook.voiceSender(context, loader);
        check(callback != null, "open native XiaoAI input first");
        AtomicInteger handoffs = new AtomicInteger();
        Class<?> sender = Reflector.findClass("ll1.f0", loader);
        // Runs after production instrumentation and the ASR probe counter. No model request or UI history
        // is created: this test proves the real front door selected the agent, not task execution quality.
        HookCallback.Unhook stop = Hooks.hookMethod(sender.getDeclaredMethod("sendToMiClaw", String.class,
            String.class, String.class, List.class), new HookCallback(-10000) {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (List.of("打开计算器", "打開計算機").contains(p.args[0])) {
                        handoffs.incrementAndGet();
                        p.setResult(null);
                    }
                }
            });
        Object speech = Reflector.callStaticMethod(Reflector.findClass("p31.g", loader), "getInstance");
        Object manager = Reflector.getObjectField(speech, "j");
        Object ownedVoiceRequest = null;
        try {
            for (String query : List.of("打开计算器", "打開計算機")) {
                int before = handoffs.get();
                MainThread.call(() -> { Reflector.callMethod(callback, "onSendClick", query, query); return null; });
                check(handoffs.get() == before + 1, "actual typed submission did not select agent");
            }
            // onSendClick also posts native voice-bar cleanup. Let those real UI callbacks settle
            // before starting an unrelated microphone fixture on the same main engine.
            Thread.sleep(800);
            NativeVoiceSubmissionProbe.submit(context, loader, "打开计算器");
            Object main = ((Map<?, ?>)Reflector.getObjectField(manager, "a")).get("main");
            if (main != null) ownedVoiceRequest = Reflector.getObjectField(main, "g");
            check(handoffs.get() == 3, "actual main ASR result did not select agent exactly once");
            for (String query : List.of("你好", "帮我查一下天气", "如何打开计算器", "不要用控屏自己操作"))
                check(!NativePhoneLanguageRouting.candidate(context, query), "ordinary/refused query became locale route");
            return "PROBE PASS: actual host locale=" + locale + "; actual typed simplified/traditional launch handoffs=2; "
                + "actual owned main ASR synthetic-final handoff=1; duplicate suppressed; native agent boundary only; "
                + "model execution and acoustic recognition untested";
        } finally {
            stop.unhook();
            Object captured = ownedVoiceRequest;
            if (captured != null) MainThread.call(() -> {
                Object main = ((Map<?, ?>)Reflector.getObjectField(manager, "a")).get("main");
                if (main != null && Reflector.getObjectField(main, "g") == captured)
                    Reflector.callMethod(main, "forceStop", true, 0);
                return null;
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
