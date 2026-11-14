package io.github.miaiplus;

import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Pins the composition the PromptBuilder hook and the standalone chat actually call, plus the
 * routing decision that forces an ordinary text submission onto the agent path.
 */
public class PromptInjectionTest {
    private static final String HOST = "你是超级小爱，可以调用 voiceassist__controlApp。";
    private static final String POLICY = "\n\n[MiAIPlus 扩展能力，覆盖上文同一工具的旧范围说明]\n逐步控屏提供两个独立函数。";
    private static final String CUSTOM = "你是一位严谨的翻译。";
    private static final String STANDALONE_SYSTEM = "你是用户选择的第三方对话模型。此请求是纯对话，没有手机工具，也没有手机屏幕上下文。";

    private static String apply(String hostText, String policy, String mode, String custom) {
        return PromptInjection.compose(hostText, policy, mode, custom);
    }

    @Test public void unknownModeReadsAsOffAndOverlongTextIsTruncatedWithoutThrowing() {
        assertEquals("off", new Config(Map.of()).customPromptMode);
        assertEquals("", new Config(Map.of()).customPromptText);
        assertFalse(new Config(Map.of()).promptRouteTextChat);
        assertFalse(new Config(Map.of()).promptApplyVoice);
        assertFalse(new Config(Map.of()).promptApplyStandaloneChat);
        for (Object mode : new Object[]{"unknown", "REPLACE", "", true, 1}) {
            assertEquals("customPromptMode=" + mode, "off",
                new Config(Map.of(Config.CUSTOM_PROMPT_MODE_KEY, mode)).customPromptMode);
        }
        String oversized = "字".repeat(Config.MAX_CUSTOM_PROMPT_CHARS + 25);
        Config config = new Config(Map.of(Config.CUSTOM_PROMPT_MODE_KEY, "append",
            Config.CUSTOM_PROMPT_TEXT_KEY, oversized,
            Config.PROMPT_ROUTE_TEXT_CHAT_KEY, "true", Config.PROMPT_APPLY_VOICE_KEY, 1));
        assertEquals("append", config.customPromptMode);
        assertEquals(Config.MAX_CUSTOM_PROMPT_CHARS, config.customPromptText.length());
        assertTrue(oversized.startsWith(config.customPromptText));
        assertFalse("non-boolean route flag", config.promptRouteTextChat);
        assertFalse("non-boolean voice flag", config.promptApplyVoice);
    }

    @Test public void effectivePromptRequiresAModeAndNonBlankText() {
        assertFalse(Config.effectivePrompt(null, null));
        assertFalse(Config.effectivePrompt("off", CUSTOM));
        assertFalse(Config.effectivePrompt("unknown", CUSTOM));
        assertFalse(Config.effectivePrompt("append", "  \n"));
        assertFalse(Config.effectivePrompt("append", null));
        assertTrue(Config.effectivePrompt("append", CUSTOM));
        assertTrue(Config.effectivePrompt("replace", " x "));
        assertTrue(new Config(Map.of(Config.CUSTOM_PROMPT_MODE_KEY, "replace",
            Config.CUSTOM_PROMPT_TEXT_KEY, CUSTOM)).hasEffectivePrompt());
    }

    @Test public void appendAddsThePromptOnceAndReplaceKeepsThePolicyComputedFromTheOriginalHostText() {
        assertEquals(HOST + POLICY, apply(HOST, POLICY, "off", CUSTOM));
        assertEquals(HOST + "\n\n" + CUSTOM + POLICY, apply(HOST, POLICY, "append", CUSTOM));
        assertEquals("already contained", HOST + "\n\n" + CUSTOM + POLICY,
            apply(HOST + "\n\n" + CUSTOM, POLICY, "append", CUSTOM));
        String replaced = apply(HOST, POLICY, "replace", CUSTOM);
        assertTrue(replaced, replaced.startsWith(CUSTOM));
        assertTrue(replaced, replaced.endsWith(POLICY));
        assertFalse("host prompt survived replace", replaced.contains("你是超级小爱"));
        // The marker check runs on the original text: a custom prompt that itself carries the marker
        // must not suppress a policy the host text still qualifies for.
        String marked = apply(HOST, POLICY, "replace", "自定义 [MiAIPlus 扩展能力 说明]");
        assertTrue(marked, marked.endsWith(POLICY));
    }

    @Test public void hostTextWithoutTheMarkerGetsThePromptButNeverThePolicy() {
        String plain = "今天天气怎么样";
        assertEquals(plain + "\n\n" + CUSTOM, apply(plain, "", "append", CUSTOM));
        assertEquals(CUSTOM, apply(plain, "", "replace", CUSTOM));
        assertEquals(plain, apply(plain, "", "off", CUSTOM));
    }

    @Test public void unavailableSettingsLeaveTheHostPromptUntouched() {
        String mode = null;
        assertEquals(HOST, apply(HOST, "", mode == null ? "off" : mode, CUSTOM));
    }

    @Test public void standaloneChatPrependsOnlyWhenOptedInAndLeavesTheScreenLoopAlone() {
        assertEquals(CUSTOM.trim() + "\n\n" + STANDALONE_SYSTEM, standaloneSystem(true, "append", CUSTOM));
        assertEquals(STANDALONE_SYSTEM, standaloneSystem(false, "append", CUSTOM));
        assertEquals(STANDALONE_SYSTEM, standaloneSystem(true, "off", CUSTOM));
        assertEquals(STANDALONE_SYSTEM, standaloneSystem(true, "replace", "  "));
    }

    @Test public void textRoutingForcesTheAgentOnlyForAnOptedInEffectivePrompt() {
        assertTrue(forceAgent(false, "", false, true, "append", CUSTOM));
        assertFalse("global model", forceAgent(true, "", false, true, "append", CUSTOM));
        assertFalse("tool requested", forceAgent(false, "miaiplus_phone_use", false, true, "append", CUSTOM));
        assertFalse("language route", forceAgent(false, "", true, true, "append", CUSTOM));
        assertFalse("switch off", forceAgent(false, "", false, false, "append", CUSTOM));
        assertFalse("mode off", forceAgent(false, "", false, true, "off", CUSTOM));
        assertFalse("blank text", forceAgent(false, "", false, true, "replace", " \n"));
    }

    @Test public void voiceHandoffRequiresTheSenderAndNeverCancelsTheTurnWithoutOne() {
        assertEquals("handoff", voiceDecision(true, "append", CUSTOM, true));
        assertEquals("sender_unavailable", voiceDecision(true, "append", CUSTOM, false));
        assertEquals("stock", voiceDecision(false, "append", CUSTOM, true));
        assertEquals("stock", voiceDecision(true, "off", CUSTOM, false));
        assertEquals("stock", voiceDecision(true, "replace", "", true));
    }

    private static String standaloneSystem(boolean applyStandalone, String mode, String text) {
        return applyStandalone
            ? PromptInjection.composeStandalone(STANDALONE_SYSTEM, mode, text) : STANDALONE_SYSTEM;
    }

    private static boolean forceAgent(boolean global, String requestedTool, boolean languageRoute,
                                      boolean routeText, String mode, String text) {
        return PromptInjection.routesTextChat(global, requestedTool, languageRoute, routeText, mode, text);
    }

    private static String voiceDecision(boolean applyVoice, String mode, String text, boolean senderAvailable) {
        if (!applyVoice || !Config.effectivePrompt(mode, text)) return "stock";
        return senderAvailable ? "handoff" : "sender_unavailable";
    }
}
