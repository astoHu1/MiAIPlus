package io.github.miaiplus;

import android.content.Context;
import java.util.Locale;
import java.util.regex.Pattern;

/** Keeps non-Chinese requests on the agent that can select this module's own phone tools. */
final class NativePhoneLanguageRouting {
    private static final Pattern DIRECT_ACTION = Pattern.compile(
        "^(?:(?:请|請|麻烦|麻煩|帮我|幫我|你|小爱|小愛)[，, ]*){0,4}"
            + "(?:打开|打開|开启|開啟|启动|啟動|點開|点开|点击|點擊|点按|點按|滑动|滑動|滚动|滾動|输入|輸入|切换|切換|操作|控屏)\\S.*"
            + "|^(?:please\\s+)?(?:open|launch|tap|click|swipe|scroll|type|control)\\s+\\S.+",
        Pattern.CASE_INSENSITIVE);
    private NativePhoneLanguageRouting() { }

    static boolean candidate(Context context, String query) {
        var locales = context.getResources().getConfiguration().getLocales();
        return candidate(locales.isEmpty() ? null : locales.get(0), query);
    }

    static boolean candidate(Locale locale, String query) {
        // Match the pinned host's zhCN check, without changing its locale, UI or old executor.
        if (query == null || query.isBlank() || locale == null
            || ("zh".equals(locale.getLanguage()) && "CN".equals(locale.getCountry()))) return false;
        String text = query.strip();
        var decision = NativeChatRouting.decide(text);
        // Respect explicit questions, advice and refusals before considering a direct action.
        if (!decision.tool().isEmpty()) return true;
        if (!"no_match".equals(decision.rule())) return false;
        return DIRECT_ACTION.matcher(text).find();
    }

    static boolean available(Context context) {
        // The authoritative catalog excludes disabled modules/plugins and withdraws tools on IPC failure.
        // Unqualified direct actions target the foreground. A background-only catalog must not
        // turn an ordinary launch into permission to use a different execution screen.
        return NativePluginTools.availableForRouting(context, PhoneUseRouting.FOREGROUND_TOOL);
    }
}
