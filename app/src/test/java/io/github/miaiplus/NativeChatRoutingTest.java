package io.github.miaiplus;

import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeChatRoutingTest {
    @Test public void explicitBackgroundPhoneTasksCanReachTheNativeTools() {
        String[] requests = {
            "请在后台打开浏览器搜索\"不要后台操作是什么意思\"",
            "请在后台打开计算器，输入123加456，告诉我结果",
            "后台帮我打开酷安搜索 MiAIPlus",
            "用虚拟屏幕查看应用页面，不要操作我的主屏",
            "请用虚拟显示器启动计算器",
            "使用 miaiplus_background_phone_use 帮我完成这个任务",
            "Please open Calculator in the background and type 123+456",
            "Use the virtual screen to launch Calculator",
            "OPEN Calculator on a VIRTUAL DISPLAY",
            "请在后台打开计算器实际输入137乘29，不要仅解释操作步骤",
            "请在后台打开浏览器搜索“什么是 AI”",
            "用虚拟屏查看设置，告诉我为什么打不开",
            "请在后台查询计算器的结果，不用解释为什么",
            "请帮我用虚拟屏看看设置页面",
            "在后台看一下应用的搜索结果",
            "Please use virtual display to search for how to use Calculator",
            "Use the virtual screen to type what is a virtual display into the search box"
        };
        for (String request : requests) assertTrue(request, NativeChatRouting.requestsBackgroundTools(request));
    }

    @Test public void ordinaryChatAndBackgroundMediaKeepTheirNativeRoute() {
        String[] requests = {
            "你好", "现在几点", "打开计算器", "在后台播放音乐", "后台音乐不要停", "帮我后台下载文件",
            "什么是虚拟屏", "如何在后台打开应用", "介绍一下后台控屏功能", "虚拟屏是否支持输入",
            "miaiplus_background_phone_use 是什么意思", "不要后台操作，打开计算器就行",
            "不用虚拟屏打开应用", "What is a virtual display?", "How do I open apps in the background?",
            "Explain miaiplus_background_phone_use", "Play music in the background", "Download this in the background",
            "Do not use the virtual screen to open Calculator",
            "后台控屏是什么", "虚拟屏支持什么操作", "请问虚拟屏是否支持输入",
            "请帮我解释后台控屏怎么操作", "  \nPlease explain virtual display input",
            "Why does background control need a virtual display?",
            "Does virtual screen support opening Calculator?", ""
        };
        for (String request : requests) assertFalse(request, NativeChatRouting.requestsBackgroundTools(request));
        assertFalse(NativeChatRouting.requestsBackgroundTools(null));
    }

    @Test public void foregroundAppActionsAndControlFollowupsReachTheNativeTools() {
        String[] requests = {
            "去抖音打开这个沙雕视频的原视频",
            "请去抖音打开这张截图对应的原视频",
            "帮我在微信里搜索这个联系人",
            "用控屏自己操作", "你用控屏自己操作吧", "使用 miaiplus_phone_use 完成刚才的任务",
            "打开抖音然后搜索这个视频", "打开浏览器，搜索这张图的出处",
            "去我的测试应用查找这个条目",
            "请在微信搜索‘通过这种方法找到答案’", "用控屏按这个方法操作",
            "Open Douyin and find the original video", "Find this video in Douyin",
            "Please control my phone and finish the task"
        };
        for (String request : requests)
            assertEquals(request, PhoneUseRouting.FOREGROUND_TOOL, NativeChatRouting.requestedPhoneTool(request));
    }

    @Test public void foregroundRoutingKeepsAdviceQuestionsAndSimpleLaunchesNative() {
        String[] requests = {
            "你好", "打开计算器", "打开抖音", "搜索原视频", "帮我查一下天气",
            "如何去抖音找到原视频", "怎么用控屏自己操作", "请帮我解释手机控屏怎么用",
            "控屏支持什么操作", "miaiplus_phone_use 是什么意思", "不要用控屏自己操作",
            "你能不能控屏？", "你支持控屏吗", "请用这个方法找到答案",
            "你不要控屏", "请你不用控屏自己操作",
            "请用中文解释如何在抖音找到原视频", "请告诉我怎么去抖音打开原视频",
            "不要后台操作，打开计算器就行", "How do I control my phone?",
            "Do not control my phone", "在后台播放音乐", "后台音乐不要停", ""
        };
        for (String request : requests) assertEquals(request, "", NativeChatRouting.requestedPhoneTool(request));
        assertEquals("", NativeChatRouting.requestedPhoneTool(null));
    }

    @Test public void explicitBackgroundTaskNeverSelectsForegroundTools() {
        assertEquals(PhoneUseRouting.BACKGROUND_TOOL,
            NativeChatRouting.requestedPhoneTool("用后台控屏去抖音打开这个视频"));
        assertEquals(PhoneUseRouting.BACKGROUND_TOOL,
            NativeChatRouting.requestedPhoneTool("在后台打开计算器输入137乘29"));
    }

    @Test public void scopeEndsAfterAnExceptionalSubmission() {
        Object callback = new Object();
        try {
            try (NativeChatRouting.Scope ignored = NativeChatRouting.begin(callback, true)) {
                assertTrue(NativeChatRouting.forcesAgent(callback));
                throw new IllegalStateException("host submission failed");
            }
        } catch (IllegalStateException expected) {
            assertEquals("host submission failed", expected.getMessage());
        }
        assertFalse(NativeChatRouting.forcesAgent(callback));
    }

    @Test public void nestedOrdinarySubmissionCannotBorrowAnOuterBackgroundRoute() {
        Object callback = new Object();
        try (NativeChatRouting.Scope outer = NativeChatRouting.begin(callback, true)) {
            try (NativeChatRouting.Scope inner = NativeChatRouting.begin(callback, false)) {
                assertFalse(NativeChatRouting.forcesAgent(callback));
            }
            assertTrue(NativeChatRouting.forcesAgent(callback));
        }
        assertFalse(NativeChatRouting.forcesAgent(callback));
    }

    @Test public void routeBelongsToTheSubmittingCallbackAndThread() throws Exception {
        Object callback = new Object();
        Object otherCallback = new Object();
        try (NativeChatRouting.Scope ignored = NativeChatRouting.begin(callback, true)) {
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
    }
}
