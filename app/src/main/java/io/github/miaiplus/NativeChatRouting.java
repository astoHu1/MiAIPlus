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

    private NativeChatRouting() { }

    static boolean requestsBackgroundTools(String query) {
        if (query == null || query.isBlank()) return false;
        String text = query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        // Search terms, text to enter and a request's final explanation can themselves
        // contain question words. Only a question at the start selects the ordinary route.
        if (EXPLANATION.matcher(text).find() || CAPABILITY_QUESTION.matcher(text).find()
            || NEGATED.matcher(text).find()) return false;
        if (text.contains(PhoneUseRouting.BACKGROUND_TOOL)) return true;
        return BACKGROUND.matcher(text).find() && ACTION.matcher(text).find();
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
