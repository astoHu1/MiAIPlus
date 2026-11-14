package io.github.miaiplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Credentials persist only in module-private storage; global mode shares them with the scoped native client in memory. */
public final class Config {
    public static final String PHONE_USE_LOG_KEY = "phoneUseDetailedLog";
    public static final String PHONE_USE_PERMISSION_MODE_KEY = "phoneUsePermissionMode";
    public static final String PHONE_USE_PERMISSION_MANUAL = "manual";
    public static final String PHONE_USE_PERMISSION_AUTO = "auto";
    public static final String PHONE_USE_PERMISSION_YOLO = "yolo";
    public static final int MAX_PHONE_USE_STEPS = 200;
    public static final int DEFAULT_CONVERSATION_TIMEOUT_MINUTES = 15;
    public static final int MAX_CONVERSATION_TIMEOUT_MINUTES = 1440;
    public static final int MIN_DISPLAY_SIZE = 320;
    public static final int MAX_DISPLAY_SIZE = 4096;
    public static final int MIN_DISPLAY_DENSITY = 80;
    public static final int MAX_DISPLAY_DENSITY = 1000;
    public static final int DEFAULT_DISPLAY_WIDTH = 1080;
    public static final int DEFAULT_DISPLAY_HEIGHT = 1920;
    public static final int DEFAULT_DISPLAY_DENSITY = 320;
    public static final String CUSTOM_PROMPT_MODE_KEY = "customPromptMode";
    public static final String CUSTOM_PROMPT_TEXT_KEY = "customPromptText";
    public static final String PROMPT_ROUTE_TEXT_CHAT_KEY = "promptRouteTextChat";
    public static final String PROMPT_APPLY_VOICE_KEY = "promptApplyVoice";
    public static final String PROMPT_APPLY_STANDALONE_CHAT_KEY = "promptApplyStandaloneChat";
    public static final String CUSTOM_PROMPT_OFF = "off";
    public static final String CUSTOM_PROMPT_APPEND = "append";
    public static final String CUSTOM_PROMPT_REPLACE = "replace";
    public static final int MAX_CUSTOM_PROMPT_CHARS = 4000;
    public final boolean enabled, globalModel, backgroundDisplay, phoneUseDetailedLog;
    /** The user's system prompt snapshot; only the module and the XiaoAi host read it, never diagnostics. */
    public final String customPromptMode, customPromptText;
    public final boolean promptRouteTextChat, promptApplyVoice, promptApplyStandaloneChat;
    public final String endpoint, apiKey, model, protocol, planner, api, reasoning;
    public final ReasoningPolicy.Plan reasoningPolicy;
    public final String phoneUsePermissionMode;
    public final String speechReplyPolicy;
    public final int maxSteps;
    public final int conversationTimeoutMinutes;
    public final int displayWidth, displayHeight, displayDensity;
    public Config(Context context) {
        this(prefs(context));
        ModelsDevCache.refreshIfDue(context);
    }
    Config(SharedPreferences prefs) { this(prefs.getAll()); }
    Config(Map<String, ?> p) {
        enabled = Boolean.TRUE.equals(p.get("enabled"));
        speechReplyPolicy = SpeechReplyPolicy.normalize(p.get(SpeechReplyPolicy.KEY));
        phoneUseDetailedLog = Boolean.TRUE.equals(p.get(PHONE_USE_LOG_KEY));
        // Default off: an existing installation keeps sending every task to the physical screen.
        backgroundDisplay = Boolean.TRUE.equals(p.get("backgroundDisplay"));
        Object permissionMode = p.get(PHONE_USE_PERMISSION_MODE_KEY);
        phoneUsePermissionMode = PHONE_USE_PERMISSION_AUTO.equals(permissionMode) ? PHONE_USE_PERMISSION_AUTO
            : PHONE_USE_PERMISSION_YOLO.equals(permissionMode) ? PHONE_USE_PERMISSION_YOLO : PHONE_USE_PERMISSION_MANUAL;
        endpoint = string(p, "endpoint", "").trim();
        apiKey = string(p, "apiKey", "");
        model = string(p, "model", "").trim();
        api = ModelProtocol.resolve(string(p, "api", "auto"), model);
        reasoningPolicy = ReasoningPolicy.plan(endpoint, model, api);
        reasoning = reasoningPolicy.normalize(
            string(p, ReasoningPolicy.preferenceKey(endpoint, model, api), "default"));
        String storedProtocol = string(p, "protocol", "phone_use");
        protocol = "computer".equals(storedProtocol) ? "phone_use" : storedProtocol;
        globalModel = Boolean.TRUE.equals(p.get("globalModel"));
        planner = globalModel ? "native" : string(p, "planner", "native");
        Object conversationTimeout = p.get("conversationTimeoutMinutes");
        conversationTimeoutMinutes = conversationTimeout instanceof Number
            ? Math.max(0, Math.min(MAX_CONVERSATION_TIMEOUT_MINUTES, ((Number) conversationTimeout).intValue()))
            : DEFAULT_CONVERSATION_TIMEOUT_MINUTES;
        displayWidth = boundedInt(p, "displayWidth", DEFAULT_DISPLAY_WIDTH, MIN_DISPLAY_SIZE, MAX_DISPLAY_SIZE);
        displayHeight = boundedInt(p, "displayHeight", DEFAULT_DISPLAY_HEIGHT, MIN_DISPLAY_SIZE, MAX_DISPLAY_SIZE);
        displayDensity = boundedInt(p, "displayDensity", DEFAULT_DISPLAY_DENSITY,
            MIN_DISPLAY_DENSITY, MAX_DISPLAY_DENSITY);
        // Prompt keys are parsed defensively: a malformed value turns the feature off instead of failing every read.
        customPromptMode = normalizePromptMode(p.get(CUSTOM_PROMPT_MODE_KEY));
        customPromptText = truncatePrompt(p.get(CUSTOM_PROMPT_TEXT_KEY) instanceof String text ? text : "");
        promptRouteTextChat = Boolean.TRUE.equals(p.get(PROMPT_ROUTE_TEXT_CHAT_KEY));
        promptApplyVoice = Boolean.TRUE.equals(p.get(PROMPT_APPLY_VOICE_KEY));
        promptApplyStandaloneChat = Boolean.TRUE.equals(p.get(PROMPT_APPLY_STANDALONE_CHAT_KEY));
        maxSteps = Math.max(1, Math.min(MAX_PHONE_USE_STEPS, p.containsKey("maxSteps") ? (Integer) p.get("maxSteps") : 30));
    }
    public static String normalizePromptMode(Object value) {
        return CUSTOM_PROMPT_APPEND.equals(value) ? CUSTOM_PROMPT_APPEND
            : CUSTOM_PROMPT_REPLACE.equals(value) ? CUSTOM_PROMPT_REPLACE : CUSTOM_PROMPT_OFF;
    }
    public static String truncatePrompt(String text) {
        if (text == null) return "";
        return text.length() <= MAX_CUSTOM_PROMPT_CHARS ? text : text.substring(0, MAX_CUSTOM_PROMPT_CHARS);
    }
    /** Whether a prompt would actually be applied: a mode is selected and the text is not blank. */
    public static boolean effectivePrompt(String mode, String text) {
        return !CUSTOM_PROMPT_OFF.equals(normalizePromptMode(mode)) && text != null && !text.trim().isEmpty();
    }
    public boolean hasEffectivePrompt() { return effectivePrompt(customPromptMode, customPromptText); }
    private static int boundedInt(Map<String, ?> values, String key, int fallback, int min, int max) {
        Object value = values.get(key);
        if (!(value instanceof Number)) return fallback;
        return Math.max(min, Math.min(max, ((Number) value).intValue()));
    }
    private static String string(Map<String, ?> values, String key, String fallback) {
        Object value = values.get(key); return value == null ? fallback : (String) value;
    }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("config", Context.MODE_PRIVATE); }
    public static String validateEndpoint(String endpoint) {
        return Endpoint.validate(endpoint);
    }
    public String completionsUrl() { return validateEndpoint(endpoint) + ModelProtocol.path(api); }
}
