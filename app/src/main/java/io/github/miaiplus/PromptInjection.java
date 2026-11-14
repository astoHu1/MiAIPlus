package io.github.miaiplus;

/**
 * Pure composition of the user's prompt with the text XiaoAi produced.
 *
 * <p>The capability policy is always computed from the original host text and appended last, so
 * replace mode still tells the model which tools exist. Keeping this out of the hook lets the
 * decision table be tested without a live host.
 */
public final class PromptInjection {
    private PromptInjection() {}

    /** Appends or replaces the user's prompt, then the policy; the host text is never mutated. */
    public static String compose(String hostText, String policy, String mode, String custom) {
        String text = hostText == null ? "" : hostText;
        String addition = policy == null ? "" : policy;
        if (!Config.effectivePrompt(mode, custom)) return text + addition;
        String prompt = custom.trim();
        // Append is idempotent: a prompt the host already carries is not duplicated.
        String base = Config.CUSTOM_PROMPT_REPLACE.equals(Config.normalizePromptMode(mode))
            ? prompt : text.contains(prompt) ? text : text + "\n\n" + prompt;
        return base + addition;
    }

    /** The standalone chat is a module-owned system prompt, so the user's text goes first. */
    public static String composeStandalone(String base, String mode, String custom) {
        return Config.effectivePrompt(mode, custom) ? custom.trim() + "\n\n" + base : base;
    }

    /**
     * A custom prompt only reaches the model on the agent path, so an opted-in ordinary text
     * submission must be forced there. Tool requests, language routing and the global model keep
     * their own precedence.
     */
    public static boolean routesTextChat(boolean global, String requestedTool, boolean languageRoute,
                                         boolean routeText, String mode, String text) {
        return routeText && !global && (requestedTool == null || requestedTool.isEmpty()) && !languageRoute
            && Config.effectivePrompt(mode, text);
    }
}
