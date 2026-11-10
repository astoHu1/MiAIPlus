package io.github.miaiplus;

import java.util.Locale;
import java.util.regex.Pattern;

/** Selects the native tool-capable chat entry, never an execution display or a phone action. */
final class NativeChatRouting {
    private static final ThreadLocal<Scope> submission = new ThreadLocal<>();
    private static final Pattern BACKGROUND = Pattern.compile(
        "后台|虚拟屏(?:幕)?|虚拟显示器|\\bbackground\\b|\\bvirtual\\s+(?:screen|display)\\b");
    private static final Pattern ACTION = Pattern.compile(
        "打开|启动|点开|点击|轻点|点按|滑动|滚动|翻页|拖动|输入|填写|搜索|查找|查询|查看|看看|看一下|读取|检查|进入|导航|浏览|选择|选中|切换|操作|控屏|执行"
            + "|\\b(?:open|launch|tap|click|swipe|scroll|type|enter|search|find|read|check|navigate|browse|operate|control|execute)\\b");
    private static final Pattern EXPLANATION = Pattern.compile(
        "^(?:(?:请|麻烦)(?:问|你|帮我)?|帮我)?(?:什么是|什么叫|如何|怎么|怎样|为什么|介绍|解释|讲解|了解)"
            + "|^(?:please\\s+)?(?:what\\s+(?:is|are)|how\\s+(?:do|does|to|can)|why|does\\s+.+support|explain|describe)\\b");
    private static final Pattern CAPABILITY_QUESTION = Pattern.compile(
        "^(?:请问)?(?:后台控屏|虚拟屏(?:幕)?|虚拟显示器|miaiplus_background_phone_use)(?:功能|这个功能)?"
            + "(?:是否|能否|支持|能不能|可不可以|是什么|是什么意思|是干什么|有什么|怎么)"
            + "|^miaiplus_background_phone_use\\s*(?:是什么|是什么意思)");
    private static final Pattern NEGATED = Pattern.compile(
        "^(?:请|麻烦)?(?:不要|别|禁止|不用|无需|不使用|不需要)(?:再|用|使用|在|通过|开启|启动|打开){0,3}(?:后台|虚拟屏|虚拟显示器)"
            + "|^(?:please\\s+)?(?:do\\s+not|don't|never|without)\\s+(?:use\\s+|the\\s+|a\\s+|in\\s+)*(?:background|virtual\\s+(?:screen|display))\\b");
    private static final Pattern PHONE_CONTROL = Pattern.compile(
        "控屏|控制(?:手机)?屏幕|操作手机|miaiplus_phone_use|\\b(?:phone\\s+use|control\\s+(?:my\\s+|the\\s+)?(?:phone|screen))\\b");
    private static final Pattern CONTROL_QUESTION = Pattern.compile(
        "^(?:请问)?(?:前台)?(?:控屏|手机控屏|控制屏幕|miaiplus_phone_use)(?:功能|这个功能)?"
            + "(?:是否|能否|支持|能不能|可不可以|是什么|是什么意思|是干什么|有什么|怎么)"
            + "|^miaiplus_phone_use\\s*(?:是什么|是什么意思)"
            + "|^(?:请问)?(?:你|小爱)?(?:能不能|能否|是否能|可不可以|支持)(?:进行)?(?:控屏|控制屏幕|操作手机)[？?吗嘛么]?$");
    private static final Pattern ADVICE_REQUEST = Pattern.compile(
        "^(?:(?:请|麻烦)(?:你|帮我)?|帮我)?(?:(?:用|使用)[^，。！？；]{1,10}?)?(?:解释|说明|介绍|讲解|告诉我(?:如何|怎么|怎样))"
            + "|^(?:(?:请|麻烦)(?:你|帮我)?|帮我)?(?:用|使用|通过)(?:这个|这种|那个|那种|上述|以下)?(?:方法|方式|技巧|公式|算法|步骤|思路)");
    private static final Pattern NO_CONTROL = Pattern.compile(
        "^(?:请|麻烦)?(?:你|小爱)?(?:不要|别|禁止|不用|无需|不使用|不需要)(?:再|用|使用)?(?:控屏|控制屏幕|操作手机)"
            + "|^(?:please\\s+)?(?:do\\s+not|don't|never)\\s+(?:use\\s+)?(?:phone\\s+use|control\\s+(?:my\\s+|the\\s+)?(?:phone|screen))\\b");
    private static final String IN_APP_ACTION = "(?:打开|点开|点击|轻点|点按|滑动|滚动|翻页|拖动|输入|填写|搜索|查找|找到|进入|选择|选中|发送|点赞|收藏|评论|关注|操作)";
    private static final Pattern APP_OPERATION = Pattern.compile(
        // An app-qualified operation, including a cross-app request derived from a screenshot.
        "(?:去|到|在|用|使用|通过)[^，。！？；\\n]{1,20}?" + IN_APP_ACTION
            + "|(?:打开|启动|进入|点开)[^，。！？；\\n]{1,20}?[，,]?" + IN_APP_ACTION
            + "|\\b(?:open|launch)\\s+.+?\\s+(?:and|then|to)\\s+(?:search|find|tap|click|type|enter|select)\\b"
            + "|\\b(?:search|find|tap|click|type|enter|select)\\b.+?\\b(?:in|on|using)\\s+\\S+");

    private NativeChatRouting() { }

    static boolean requestsBackgroundTools(String query) {
        return PhoneUseRouting.BACKGROUND_TOOL.equals(decide(query).tool());
    }

    /** Returns only the requested capability; plugin availability is checked at submission. */
    static String requestedPhoneTool(String query) { return decide(query).tool(); }

    /**
     * The requested capability and the rule that decided it. The rule is a fixed label, never the wording: it
     * is what a diagnostic record keeps, so "the request was not sent to the agent" can be traced to a pattern.
     */
    record Decision(String tool, String rule) { }

    static Decision decide(String query) {
        if (query == null || query.isBlank()) return new Decision("", "blank");
        String text = query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        // Search terms, text to enter and a request's final explanation can themselves
        // contain question words. Only a question at the start selects the ordinary route.
        boolean explanation = EXPLANATION.matcher(text).find(), capability = CAPABILITY_QUESTION.matcher(text).find();
        boolean negated = NEGATED.matcher(text).find(), background = BACKGROUND.matcher(text).find();
        if (!explanation && !capability && !negated) {
            if (text.contains(PhoneUseRouting.BACKGROUND_TOOL))
                return new Decision(PhoneUseRouting.BACKGROUND_TOOL, "background_tool_name");
            if (background && ACTION.matcher(text).find())
                return new Decision(PhoneUseRouting.BACKGROUND_TOOL, "background_action");
        }
        if (explanation) return new Decision("", "explanation");
        if (capability) return new Decision("", "capability_question");
        if (CONTROL_QUESTION.matcher(text).find()) return new Decision("", "control_question");
        if (NO_CONTROL.matcher(text).find()) return new Decision("", "no_control");
        if (ADVICE_REQUEST.matcher(text).find()) return new Decision("", "advice_request");
        if (negated) return new Decision("", "negated_background");
        if (background) return new Decision("", "background_without_action");
        if (PHONE_CONTROL.matcher(text).find()) return new Decision(PhoneUseRouting.FOREGROUND_TOOL, "phone_control");
        if (APP_OPERATION.matcher(text).find()) return new Decision(PhoneUseRouting.FOREGROUND_TOOL, "app_operation");
        return new Decision("", "no_match");
    }

    static Scope begin(Object callback, boolean forceAgent) {
        Scope scope = new Scope(callback, forceAgent, submission.get());
        submission.set(scope);
        return scope;
    }

    static boolean forcesAgent(Object callback) {
        Scope current = submission.get();
        return current != null && current.callback == callback && current.forceAgent;
    }

    static final class Scope implements AutoCloseable {
        private final Object callback;
        private final boolean forceAgent;
        private final Scope previous;
        private boolean closed;

        private Scope(Object callback, boolean forceAgent, Scope previous) {
            this.callback = callback;
            this.forceAgent = forceAgent;
            this.previous = previous;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) submission.remove();
            else submission.set(previous);
        }
    }
}
