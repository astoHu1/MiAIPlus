package io.github.miaiplus;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativePhoneLanguageRoutingTest {
    @Test public void nonSimplifiedChineseSubmissionsCanReachToolCapableAgentWithoutGuessingTheirLanguage() {
        for (Locale locale : new Locale[]{Locale.US, Locale.TAIWAN, Locale.JAPAN, Locale.forLanguageTag("ar-EG")}) {
            assertTrue(locale.toString(), NativePhoneLanguageRouting.candidate(locale, "打开计算器"));
            assertTrue(locale.toString(), NativePhoneLanguageRouting.candidate(locale, "請打開計算機"));
            assertTrue(locale.toString(), NativePhoneLanguageRouting.candidate(locale, "Please open Calculator"));
        }
    }

    @Test public void simplifiedChineseKeepsExistingIntentRouting() {
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.SIMPLIFIED_CHINESE, "打开计算器"));
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.forLanguageTag("zh-Hans-CN"), "打开计算器"));
    }

    @Test public void absentLocaleAndEmptyInputCannotStartAHandoff() {
        assertFalse(NativePhoneLanguageRouting.candidate((Locale)null, "打开计算器"));
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.US, null));
        assertFalse(NativePhoneLanguageRouting.candidate(Locale.US, "  \n "));
    }

    @Test public void localeNeverOverridesOrdinarySkillsAdviceOrAnExplicitRefusal() {
        for (String query : new String[]{"你好", "现在几点", "帮我查一下天气", "设定明天七点闹钟",
            "如何打开计算器", "不要用控屏自己操作", "Do not control my phone", "What is phone use?",
            "Explain how to open Calculator", "在后台播放音乐", "不要后台操作，打开计算器就行"}) {
            assertFalse(query, NativePhoneLanguageRouting.candidate(Locale.US, query));
        }
    }
}
